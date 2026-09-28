package com.example.sdlc.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.artifact.ArtifactStore;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.PlanProposal;
import com.example.sdlc.plan.PlanRejectedException;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.policy.ChangeContext;
import com.example.sdlc.policy.PolicyDecision;
import com.example.sdlc.policy.PolicyVerdict;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.FileDelta;
import com.example.sdlc.workspace.Workspace;
import com.example.sdlc.workspace.WorkspaceException;

import tools.jackson.databind.JsonNode;

/**
 * One execution (or resumption) of a run. The coordinator thread that calls {@link #execute} is the only
 * thread that changes run state, writes the workspace or appends events; handlers run on the worker pool.
 *
 * <p>Sections: scheduling, results, change governance, failures and recovery, human checkpoints, run
 * outcomes. Any unexpected exception on the coordinator is turned into a safe stop rather than escaping.
 */
final class RunExecution {

    private static final String CAUSE_ATTEMPT_REJECTED = "attempt-rejected";
    private static final String CAUSE_SAFE_STOP = "safe-stop";
    private static final String CAUSE_ORPHANED = "orphaned";
    private static final int EXCERPT_LINES_PER_FILE = 12;
    private static final int EXCERPT_LINES_TOTAL = 80;

    private record WorkerOutcome(String taskId, int generation, int attempt, String capability, boolean fallback,
                                 TaskResult result, Throwable error, List<ArtifactRef> consumed,
                                 Instant startedAt, String thread) {
    }

    private final WorkflowEngine engine;
    private final WorkflowRun run;
    private final Workspace workspace;
    private final EventLog log;
    private final Consumer<WorkflowRun> checkpoint;
    private final Replanner replanner;
    private final ExecutorService pool;
    private final CompletionService<WorkerOutcome> completions;
    private int running;
    private String haltReason;

    RunExecution(WorkflowEngine engine, WorkflowRun run, Workspace workspace, EventLog log, Consumer<WorkflowRun> checkpoint) {
        this.engine = engine;
        this.run = run;
        this.workspace = workspace;
        this.log = log;
        this.checkpoint = checkpoint;
        this.replanner = new Replanner(run, workspace, log, this::changesWorkspace, () -> checkpoint.accept(run));
        this.pool = Executors.newFixedThreadPool(engine.settings.parallelism(), Thread.ofPlatform().name("worker-", 1).factory());
        this.completions = new ExecutorCompletionService<>(pool);
    }

    RunStatus execute() {
        boolean resumed = run.status() != RunStatus.CREATED;
        run.status(RunStatus.RUNNING, null);
        log.append(resumed ? EventType.RUN_RESUMED : EventType.RUN_STARTED, null, null,
                (resumed ? "resumed " : "started ") + run.title(),
                "planVersion", run.plan().version(), "tasks", run.plan().tasks().size(),
                "workspaceHash", workspace.contentHash());
        try {
            guarded(() -> {
                reconcileAppliedChanges();
                recoverOrphanedAttempts();
                List<String> answered = run.humanRequests().stream()
                        .filter(r -> r.status() == HumanRequest.Status.ANSWERED)
                        .map(HumanRequest::id)
                        .toList();
                for (String requestId : answered) {
                    if (haltReason != null) {
                        break;
                    }
                    applyResponse(requestId);
                }
            });
            loop();
        } finally {
            pool.shutdownNow();
            checkpoint.accept(run);
        }
        return run.status();
    }

    RunStatus stop(String reason) {
        try {
            requestHalt(reason);
            // The snapshot may predate a rollback that already happened; compensation must start from the real disk.
            guarded(this::reconcileAppliedChanges);
            safeStop();
        } finally {
            pool.shutdownNow();
            checkpoint.accept(run);
        }
        return run.status();
    }

    private void loop() {
        while (true) {
            try {
                if (haltReason == null) {
                    promote();
                    dispatch();
                }
                if (running > 0) {
                    handle(nextCompletion());
                    continue;
                }
                if (haltReason != null) {
                    safeStop();
                    return;
                }
                List<TaskState> active = run.activeTasks();
                if (active.stream().allMatch(t -> t.status() == TaskStatus.SUCCEEDED)) {
                    complete();
                    return;
                }
                if (active.stream().anyMatch(t -> t.status() == TaskStatus.READY)) {
                    continue;
                }
                if (active.stream().anyMatch(t -> t.status() == TaskStatus.AWAITING_HUMAN)) {
                    pause();
                    return;
                }
                requestHalt("no runnable work remains: " + active.stream()
                        .filter(t -> t.status() != TaskStatus.SUCCEEDED)
                        .map(t -> t.taskId() + "=" + t.status()).collect(Collectors.joining(", ")));
            } catch (RuntimeException e) {
                coordinatorError(e);
            }
        }
    }

    /** The fault barrier: an engine error is an unrecoverable failure like any other, so it ends in a safe stop. */
    private void guarded(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            coordinatorError(e);
        }
    }

    private void coordinatorError(RuntimeException error) {
        requestHalt("coordinator error: " + WorkflowEngine.describe(error));
    }

    private WorkerOutcome nextCompletion() {
        try {
            return completions.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = 0;
            throw new IllegalStateException("interrupted while waiting for workers", e);
        } catch (ExecutionException e) {
            running--;
            throw new IllegalStateException("worker wrapper failed", e.getCause());
        }
    }

    // ---------------------------------------------------------------- scheduling

    private void promote() {
        for (TaskSpec spec : run.plan().topologicalOrder()) {
            TaskState state = run.task(spec.id());
            if (state.status() != TaskStatus.PENDING) {
                continue;
            }
            Optional<String> failedPrerequisite = spec.dependsOn().stream()
                    .filter(d -> run.task(d).status().preventsDependents())
                    .findFirst();
            if (failedPrerequisite.isPresent()) {
                state.status(TaskStatus.BLOCKED);
                state.detail("prerequisite " + failedPrerequisite.get() + " is " + run.task(failedPrerequisite.get()).status());
                log.append(EventType.TASK_BLOCKED, spec.id(), null, state.detail(), "blockedBy", failedPrerequisite.get());
                continue;
            }
            if (!ancestorsSucceeded(spec) || !passesEntryGates(spec, state)) {
                continue;
            }
            state.status(TaskStatus.READY);
            log.append(EventType.TASK_READY, spec.id(), null, "prerequisites satisfied",
                    "dependsOn", spec.dependsOn(), "stage", spec.stage());
        }
    }

    private boolean ancestorsSucceeded(TaskSpec spec) {
        return run.plan().ancestors(spec.id()).stream().allMatch(a -> run.task(a).status() == TaskStatus.SUCCEEDED);
    }

    private boolean passesEntryGates(TaskSpec spec, TaskState state) {
        for (String gateName : union(capability(spec, state).entryGates(), spec.entryGates())) {
            GateResult result = evaluate(gateName, new GateContext(run, spec, state, null, workspace));
            if (result.verdict() == GateResult.Verdict.PASS) {
                log.append(EventType.GATE_PASSED, spec.id(), null, gateName + ": " + result.summary(),
                        "gate", gateName, "phase", "entry");
                continue;
            }
            log.append(EventType.GATE_FAILED, spec.id(), null, gateName + ": " + result.summary(),
                    "gate", gateName, "phase", "entry", "details", result.details());
            failTask(spec, state, FailureKind.FATAL, "entry gate " + gateName + " failed: " + result.summary());
            return false;
        }
        return true;
    }

    private void dispatch() {
        for (TaskSpec spec : run.plan().topologicalOrder()) {
            if (running >= engine.settings.parallelism()) {
                return;
            }
            TaskState state = run.task(spec.id());
            if (state.status() != TaskStatus.READY) {
                continue;
            }
            // A task that became READY may lose eligibility if an ancestor was invalidated in the meantime.
            if (!ancestorsSucceeded(spec)) {
                state.status(TaskStatus.PENDING);
                continue;
            }
            Capability capability = capability(spec, state);
            state.startAttempt();
            TaskContext context = contextFor(spec, state);
            Instant startedAt = engine.clock.instant();
            log.append(EventType.TASK_STARTED, spec.id(), state.attempt(),
                    capability.name() + (state.onFallback() ? " (fallback)" : ""),
                    "capability", capability.name(), "generation", state.generation(), "fallback", state.onFallback(),
                    "stage", spec.stage(), "maxAttempts", attemptBudget(spec, state), "inFlight", running + 1);
            int generation = state.generation();
            int attempt = state.attempt();
            boolean fallback = state.onFallback();
            completions.submit(() -> work(capability, context, spec.id(), generation, attempt, fallback, startedAt));
            running++;
        }
    }

    private WorkerOutcome work(Capability capability, TaskContext context, String taskId, int generation, int attempt,
                               boolean fallback, Instant startedAt) {
        String thread = Thread.currentThread().getName();
        try {
            TaskResult result = capability.handler().execute(context);
            if (result == null) {
                throw new TaskFailure(FailureKind.INVALID_OUTPUT, "handler returned no result");
            }
            return new WorkerOutcome(taskId, generation, attempt, capability.name(), fallback, result, null,
                    context.consumed(), startedAt, thread);
        } catch (Throwable error) {
            return new WorkerOutcome(taskId, generation, attempt, capability.name(), fallback, null, error,
                    context.consumed(), startedAt, thread);
        }
    }

    private TaskContext contextFor(TaskSpec spec, TaskState state) {
        Set<String> ancestors = run.plan().ancestors(spec.id());
        Map<String, Artifact> visible = new HashMap<>();
        for (Artifact artifact : run.artifacts().currentArtifacts()) {
            if (ancestors.contains(artifact.producer()) || artifact.producer().startsWith("human:")
                    || artifact.producer().equals("engine")) {
                visible.put(artifact.key(), artifact);
            }
        }
        Map<String, ArtifactRef> fileWriters = new HashMap<>();
        for (AppliedChangeSet applied : run.appliedChanges()) {
            run.artifacts().current(ArtifactKeys.changesOf(applied.taskId()))
                    .ifPresent(changes -> applied.paths().forEach(path -> fileWriters.put(path, changes.ref())));
        }
        return new TaskContext(run.requirement(), spec, state.attempt(), state.executions(), visible, fileWriters,
                workspace, state.feedback());
    }

    // ---------------------------------------------------------------- results

    private void handle(WorkerOutcome outcome) {
        running--;
        TaskState state = run.task(outcome.taskId());
        Optional<TaskSpec> spec = run.plan().find(outcome.taskId());
        boolean current = spec.isPresent() && state.status() == TaskStatus.RUNNING
                && state.generation() == outcome.generation() && state.attempt() == outcome.attempt();
        if (!current) {
            state.record(attemptRecord(outcome, AttemptRecord.Outcome.DISCARDED, "superseded while running"));
            log.append(EventType.ATTEMPT_DISCARDED, outcome.taskId(), outcome.attempt(),
                    "result discarded: task was invalidated while this attempt was running");
            if (spec.isPresent() && state.status() == TaskStatus.RUNNING) {
                state.status(haltReason == null ? TaskStatus.PENDING : TaskStatus.CANCELLED);
            }
            return;
        }
        if (haltReason != null) {
            state.record(attemptRecord(outcome, AttemptRecord.Outcome.CANCELLED, "safe stop in progress"));
            state.status(TaskStatus.CANCELLED);
            state.detail("result discarded during safe stop");
            log.append(EventType.TASK_CANCELLED, outcome.taskId(), outcome.attempt(), "result discarded during safe stop");
            return;
        }
        if (outcome.error() != null) {
            AttemptResult failed = AttemptResult.failed(outcome.taskId(), outcome.generation(), outcome.attempt(),
                    outcome.consumed(), outcome.startedAt(), outcome.thread());
            List<String> details = outcome.error() instanceof TaskFailure failure ? failure.details() : List.of();
            failAttempt(spec.get(), state, failed, WorkflowEngine.classify(outcome.error()),
                    WorkflowEngine.describe(outcome.error()), details, List.of());
            return;
        }
        List<ArtifactRef> stale = outcome.consumed().stream().filter(ref -> !run.artifacts().isCurrent(ref)).toList();
        if (!stale.isEmpty()) {
            state.record(attemptRecord(outcome, AttemptRecord.Outcome.DISCARDED, "stale inputs " + stale));
            state.newGeneration();
            state.status(TaskStatus.PENDING);
            log.append(EventType.ATTEMPT_DISCARDED, outcome.taskId(), outcome.attempt(),
                    "inputs changed while running; re-running", "staleInputs", stale.stream().map(Object::toString).toList());
            return;
        }
        AttemptResult result = new AttemptResult(outcome.taskId(), outcome.generation(), outcome.attempt(),
                outcome.result().outputs(), outcome.result().changes(), outcome.consumed(), outcome.result().summary(),
                null, outcome.startedAt(), outcome.thread());
        continueAttempt(spec.get(), state, result, 0, null);
    }

    /**
     * @param approvedFingerprint fingerprint of the change a human approved for this attempt, or {@code null}
     */
    private void continueAttempt(TaskSpec spec, TaskState state, AttemptResult result, int gateIndex, String approvedFingerprint) {
        state.status(TaskStatus.RUNNING);
        Capability capability = capability(spec, state);
        if (result.changes() != null && !capability.proposesChanges()) {
            failAttempt(spec, state, result, FailureKind.INVALID_OUTPUT,
                    "capability " + capability.name() + " may not change the workspace", List.of(), List.of());
            return;
        }
        PlanValidator.Validated plan = null;
        if (capability.producesPlan()) {
            Optional<PlanValidator.Validated> validated = validatePlan(spec, state, result);
            if (validated.isEmpty()) {
                return;
            }
            plan = validated.get();
        }
        if (result.changes() != null && result.appliedChangeSetId() == null) {
            Optional<AttemptResult> applied = governAndApply(spec, state, result, approvedFingerprint);
            if (applied.isEmpty()) {
                return;
            }
            result = applied.get();
        }
        List<String> exitGates = exitGates(spec, state);
        for (int i = gateIndex; i < exitGates.size(); i++) {
            String gateName = exitGates.get(i);
            GateResult verdict = evaluate(gateName, new GateContext(run, spec, state, result, workspace));
            switch (verdict.verdict()) {
                case PASS -> log.append(EventType.GATE_PASSED, spec.id(), state.attempt(), gateName + ": " + verdict.summary(),
                        "gate", gateName, "phase", "exit");
                case FAIL -> {
                    log.append(EventType.GATE_FAILED, spec.id(), state.attempt(), gateName + ": " + verdict.summary(),
                            "gate", gateName, "phase", "exit", "details", verdict.details(), "kind", verdict.failureKind(),
                            "suspects", verdict.suspects().isEmpty() ? null : verdict.suspects());
                    rollbackAttempt(result, "exit gate " + gateName + " failed");
                    failAttempt(spec, state, result, verdict.failureKind(), gateName + ": " + verdict.summary(),
                            verdict.details(), verdict.suspects());
                    return;
                }
                case NEEDS_HUMAN -> {
                    log.append(EventType.GATE_WAITING, spec.id(), state.attempt(), gateName + ": " + verdict.summary(),
                            "gate", gateName, "phase", "exit");
                    TaskState.ParkedAt at = verdict.humanNeed().kind() == HumanRequest.Kind.CLARIFICATION
                            ? TaskState.ParkedAt.CLARIFICATION : TaskState.ParkedAt.GATE;
                    park(run.nextId("hr"), spec, state, at, result, i, gateName, verdict.humanNeed(), List.of(), null);
                    return;
                }
            }
        }
        commit(spec, state, result, plan);
    }

    /** Plan governance runs before the planner's result is accepted; violations go back to the planner. */
    private Optional<PlanValidator.Validated> validatePlan(TaskSpec spec, TaskState state, AttemptResult result) {
        OutputArtifact output = result.outputs().get(ArtifactKeys.PLAN_PROPOSAL);
        if (output == null) {
            failAttempt(spec, state, result, FailureKind.INVALID_OUTPUT, "no plan proposal produced", List.of(), List.of());
            return Optional.empty();
        }
        PlanProposal proposal = output.as(PlanProposal.class);
        String baseline = run.plan().contains(WorkflowEngine.BASELINE_TASK) ? WorkflowEngine.BASELINE_TASK : null;
        try {
            PlanValidator.Validated validated = engine.planValidator.validate(run.plan(), proposal.tasks(), proposal.rationale(),
                    spec.id(), baseline);
            log.append(EventType.GATE_PASSED, spec.id(), state.attempt(), "plan-valid: " + proposal.tasks().size()
                            + " tasks validated; max parallel width " + maxWidth(validated.plan())
                            + (validated.notes().isEmpty() ? "" : "; " + validated.notes()),
                    "gate", "plan-valid", "phase", "exit");
            return Optional.of(validated);
        } catch (PlanRejectedException e) {
            log.append(EventType.GATE_FAILED, spec.id(), state.attempt(), "plan-valid: plan violates governance rules",
                    "gate", "plan-valid", "phase", "exit", "details", e.violations(), "kind", FailureKind.GATE_FAILED);
            failAttempt(spec, state, result, FailureKind.GATE_FAILED, "plan-valid: plan violates governance rules",
                    e.violations(), List.of());
            return Optional.empty();
        }
    }

    private void commit(TaskSpec spec, TaskState state, AttemptResult result, PlanValidator.Validated plan) {
        Capability capability = capability(spec, state);
        Instant now = engine.clock.instant();
        ArtifactStore artifacts = run.artifacts();
        List<ArtifactRef> changed = new ArrayList<>();
        List<String> produced = new ArrayList<>();
        Map<String, OutputArtifact> outputs = new TreeMap<>(result.outputs());
        if (result.appliedChangeSetId() != null) {
            AppliedChangeSet applied = applied(result.appliedChangeSetId()).orElseThrow();
            outputs.put(ArtifactKeys.changesOf(spec.id()), OutputArtifact.of("change-set", Map.of(
                    "changeSet", applied.id(),
                    "sequence", sequenceOf(applied.id()),
                    "summary", applied.summary(),
                    "files", applied.files().stream().map(f -> Map.of("path", f.path(), "op", f.op().name(),
                            "postHash", f.postHash() == null ? "deleted" : f.postHash())).toList())));
        }
        ArtifactStore.Change planChange = null;
        for (Map.Entry<String, OutputArtifact> entry : outputs.entrySet()) {
            ArtifactStore.Publication publication = artifacts.publish(entry.getKey(), entry.getValue().kind(),
                    entry.getValue().content(), spec.id(), state.attempt(), result.inputs(), now);
            produced.add(entry.getKey());
            if (entry.getKey().equals(ArtifactKeys.PLAN_PROPOSAL)) {
                planChange = publication.change();
            }
            if (publication.change() == ArtifactStore.Change.UNCHANGED) {
                log.append(EventType.ARTIFACT_UNCHANGED, spec.id(), state.attempt(),
                        publication.artifact().ref() + " unchanged (downstream work stays valid)",
                        "artifact", publication.artifact().ref().toString());
            } else {
                if (publication.previous() != null) {
                    changed.add(publication.previous().ref());
                }
                log.append(EventType.ARTIFACT_PUBLISHED, spec.id(), state.attempt(), "published " + publication.artifact().ref(),
                        "artifact", publication.artifact().ref().toString(), "kind", entry.getValue().kind(),
                        "inputs", result.inputs().stream().map(Object::toString).toList(),
                        "supersedes", publication.previous() == null ? null : publication.previous().ref().toString());
            }
        }
        for (Artifact previous : artifacts.producedBy(spec.id())) {
            if (!produced.contains(previous.key())) {
                artifacts.retract(previous.key(), "no longer produced by " + spec.id());
                changed.add(previous.ref());
            }
        }
        state.succeeded(result.inputs(), produced);
        state.record(record(state, capability.name(), result.startedAt(), AttemptRecord.Outcome.SUCCEEDED, null, result.summary()));
        state.detail(result.summary());
        log.append(EventType.TASK_SUCCEEDED, spec.id(), state.attempt(), result.summary(),
                "capability", capability.name(), "fallback", state.onFallback(), "stage", spec.stage(),
                "durationMs", Duration.between(result.startedAt(), now).toMillis(), "thread", result.thread(),
                "outputs", produced, "inputs", result.inputs().stream().map(Object::toString).toList());
        if (plan != null) {
            if (planChange == ArtifactStore.Change.UNCHANGED) {
                log.append(EventType.ARTIFACT_UNCHANGED, spec.id(), state.attempt(),
                        "plan proposal unchanged; keeping plan v" + run.plan().version(), "artifact", ArtifactKeys.PLAN_PROPOSAL);
            } else {
                boolean firstPlan = run.planHistory().size() == 1;
                replanner.adopt(plan.plan(), plan.notes());
                if (!firstPlan) {
                    countReplan("plan revised to v" + plan.plan().version());
                }
            }
        }
        if (!changed.isEmpty()) {
            Set<String> affected = replanner.propagate(changed, spec.id() + " produced new versions of "
                    + changed.stream().map(ArtifactRef::key).distinct().toList());
            if (!affected.isEmpty()) {
                countReplan("upstream outputs of " + spec.id() + " changed; invalidated " + affected);
            }
        }
        checkpoint.accept(run);
    }

    // ---------------------------------------------------------------- change governance

    /** Policy evaluation and application of a proposed change set; empty when the attempt stopped here. */
    private Optional<AttemptResult> governAndApply(TaskSpec spec, TaskState state, AttemptResult result, String approvedFingerprint) {
        List<FileDelta> deltas;
        try {
            deltas = workspace.preview(result.changes());
        } catch (WorkspaceException e) {
            failAttempt(spec, state, result, WorkflowEngine.kindOf(e), e.getMessage(), List.of(), List.of());
            return Optional.empty();
        } catch (RuntimeException e) {
            failAttempt(spec, state, result, FailureKind.INVALID_OUTPUT, "proposal cannot be applied: " + WorkflowEngine.describe(e),
                    List.of(), List.of());
            return Optional.empty();
        }
        if (deltas.isEmpty()) {
            failAttempt(spec, state, result, FailureKind.INVALID_OUTPUT, "proposal contains no changes", List.of(), List.of());
            return Optional.empty();
        }
        PolicyVerdict verdict = engine.policy.evaluate(new ChangeContext(spec.id(), spec.scope(), deltas, anticipatedPaths(),
                result.changes().declaredRisk(), workspace::existsInBaseline));
        List<String> approvalRules = verdict.ruleIds(PolicyDecision.REQUIRE_APPROVAL);
        String fingerprint = fingerprint(deltas, approvalRules);
        boolean recheck = approvedFingerprint != null;
        log.append(EventType.POLICY_EVALUATED, spec.id(), state.attempt(),
                "policy " + verdict.decision() + (recheck ? " (re-checked after human approval)" : "")
                        + " for " + deltas.size() + " file(s): " + result.changes().summary(),
                "decision", verdict.decision(), "findings", verdict.describe(), "humanApproved", recheck,
                "generation", state.generation(), "rules", verdict.ruleIds(PolicyDecision.ALLOW),
                "declaredRisk", result.changes().declaredRisk(),
                "files", deltas.stream().map(d -> d.op() + " " + d.path()).toList());
        if (verdict.decision() == PolicyDecision.DENY) {
            failAttempt(spec, state, result, FailureKind.POLICY_DENIED, "policy denied the proposed change",
                    verdict.findings().stream().filter(f -> f.decision() == PolicyDecision.DENY).map(f -> f.describe()).toList(),
                    List.of());
            return Optional.empty();
        }
        // An approval covers exactly the change and rules that were shown; anything else needs a new decision.
        if (verdict.decision() == PolicyDecision.REQUIRE_APPROVAL && !fingerprint.equals(approvedFingerprint)) {
            String requestId = run.nextId("hr");
            List<String> details = new ArrayList<>();
            if (recheck) {
                details.add("The proposal or its policy findings changed after the previous approval; a new decision is needed.");
            }
            deltas.forEach(d -> details.add(d.op() + " " + d.path()));
            details.addAll(verdict.describe());
            details.add("Complete diff: " + HumanRequest.approvalPatchPath(requestId) + " in the run directory");
            details.addAll(excerpt(deltas));
            // Published before park() checkpoints, so the evidence is durable before the reviewer is asked.
            publishChangeReview(requestId, spec, state, fingerprint, deltas);
            park(requestId, spec, state, TaskState.ParkedAt.CHANGE_APPROVAL, result, 0, null,
                    new GateResult.HumanNeed(HumanRequest.Kind.CHANGE_APPROVAL,
                            "Approve change set for " + spec.id() + ": " + result.changes().summary(), details, List.of()),
                    approvalRules, fingerprint);
            return Optional.empty();
        }
        Workspace.Prepared prepared;
        try {
            prepared = workspace.prepare(run.nextId("cs"), spec.id(), state.attempt(), result.changes());
        } catch (WorkspaceException e) {
            failAttempt(spec, state, result, WorkflowEngine.kindOf(e), e.getMessage(), List.of(), List.of());
            return Optional.empty();
        }
        AppliedChangeSet applied = prepared.applied();
        // Write-ahead: the compensation record is durable before the first file changes, so a crash while writing
        // is reconciled on restart like an interrupted rollback instead of leaving unrecorded changes behind.
        run.pushApplied(applied);
        checkpoint.accept(run);
        try {
            workspace.write(prepared);
        } catch (RuntimeException e) {
            // Undoes only what was written; if even that fails, the error stops the run with the record still stacked.
            workspace.completeRollback(applied);
            run.removeApplied(applied.id());
            checkpoint.accept(run);
            failAttempt(spec, state, result, WorkflowEngine.classify(e), "change set could not be written: "
                    + WorkflowEngine.describe(e), List.of(), List.of());
            return Optional.empty();
        }
        log.append(EventType.CHANGESET_APPLIED, spec.id(), state.attempt(),
                "applied " + applied.id() + ": " + applied.files().size() + " file(s)",
                "changeSet", applied.id(), "files", applied.paths(), "workspaceHash", workspace.contentHash());
        // Durable before the exit gates run, so a crash here can still be compensated on resume.
        checkpoint.accept(run);
        return Optional.of(result.withApplied(applied.id()));
    }

    private static String fingerprint(List<FileDelta> deltas, List<String> rules) {
        StringBuilder text = new StringBuilder();
        deltas.forEach(d -> text.append(d.op()).append(' ').append(d.path()).append(' ')
                .append(d.after() == null ? "-" : Json.sha256(d.after())).append('\n'));
        text.append("rules ").append(rules.stream().sorted().toList());
        return Json.sha256(text.toString());
    }

    /**
     * Evidence for a change approval: the complete diff of exactly the deltas behind {@code fingerprint}, kept as an
     * engine artifact (and written next to the run by the run store) so the decision can be audited later.
     */
    private void publishChangeReview(String requestId, TaskSpec spec, TaskState state, String fingerprint, List<FileDelta> deltas) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", requestId);
        content.put("taskId", spec.id());
        content.put("generation", state.generation());
        content.put("attempt", state.attempt());
        content.put("fingerprint", fingerprint);
        content.put("files", deltas.stream().map(d -> Map.of("op", d.op().name(), "path", d.path())).toList());
        content.put("diff", Workspace.unifiedDiff(deltas, run.setting("patchPrefix", "")));
        ArtifactStore.Publication publication = run.artifacts().publish(HumanRequest.CHANGE_REVIEW_PREFIX + requestId,
                "change-diff", Json.tree(content), "engine", 0, List.of(), engine.clock.instant());
        log.append(EventType.ARTIFACT_PUBLISHED, spec.id(), state.attempt(), "published " + publication.artifact().ref()
                + " (diff shown for approval)", "artifact", publication.artifact().ref().toString(), "kind", "change-diff");
    }

    /** The removed and added lines a reviewer must see to make an informed decision (bounded). */
    private static List<String> excerpt(List<FileDelta> deltas) {
        List<String> lines = new ArrayList<>();
        for (FileDelta delta : deltas) {
            if (lines.size() >= EXCERPT_LINES_TOTAL) {
                lines.add("... (excerpt truncated)");
                break;
            }
            // The line diff behind the patch, not a set difference: removing one of two identical lines must show.
            Workspace.LineChanges changes = Workspace.lineChanges(delta);
            List<String> removed = changes.removed();
            List<String> added = changes.added();
            lines.add("--- " + delta.path() + (delta.op() == FileChange.Op.DELETE ? ": file deleted" : "")
                    + " (" + removed.size() + " removed, " + added.size() + " added line(s))");
            excerptOf(removed, "- ", lines);
            excerptOf(added, "+ ", lines);
        }
        return lines;
    }

    private static void excerptOf(List<String> changed, String marker, List<String> lines) {
        // A carriage return is shown, not sent to the reviewer's terminal (a CRLF-only change must stay visible).
        changed.stream().limit(EXCERPT_LINES_PER_FILE).forEach(line -> lines.add(marker + line.replace("\r", "\\r")));
        if (changed.size() > EXCERPT_LINES_PER_FILE) {
            lines.add(marker + "... " + (changed.size() - EXCERPT_LINES_PER_FILE) + " more");
        }
    }

    // ---------------------------------------------------------------- failures and recovery

    private void failAttempt(TaskSpec spec, TaskState state, AttemptResult result, FailureKind kind, String message,
                             List<String> details, List<String> suspects) {
        state.record(record(state, capability(spec, state).name(), result.startedAt(), AttemptRecord.Outcome.FAILED, kind, message));
        keepAsEvidence(spec, state, result, message);
        state.addFeedback(new Feedback(spec.id(), kind, message, details));
        state.clearParked();
        log.append(EventType.ATTEMPT_FAILED, spec.id(), state.attempt(), message,
                "kind", kind, "details", details, "stage", spec.stage());

        if (kind == FailureKind.CODE_DEFECT && !spec.verifies().isEmpty()) {
            if (!suspects.isEmpty() && spec.verifies().stream().noneMatch(suspects::contains)) {
                // Reworking the declared coverage would redo work the evidence does not implicate and leave the
                // implicated work as it is; nothing this verifier may send back can fix it.
                String outside = "defect suspected in " + suspects + ", outside the declared coverage " + spec.verifies();
                log.append(EventType.REWORK_REQUESTED, spec.id(), state.attempt(), "cannot rework: " + outside,
                        "targets", List.of(), "suspects", suspects, "verifies", spec.verifies(), "possible", false);
                failTask(spec, state, kind, outside + ": " + message);
                return;
            }
            if (!rework(spec, state, message, details, suspects)) {
                failTask(spec, state, kind, "rework budget exhausted: " + message);
            }
            return;
        }
        int budget = attemptBudget(spec, state);
        if (kind.retryable() && state.attempt() < budget) {
            state.status(TaskStatus.PENDING);
            log.append(EventType.RETRY_SCHEDULED, spec.id(), state.attempt(),
                    "retry " + (state.attempt() + 1) + "/" + budget + " after " + kind,
                    "kind", kind, "nextAttempt", state.attempt() + 1, "maxAttempts", budget);
            return;
        }
        if (kind.fallbackEligible() && !state.onFallback() && spec.fallback() != null) {
            state.switchToFallback();
            state.status(TaskStatus.PENDING);
            log.append(EventType.FALLBACK_ACTIVATED, spec.id(), null,
                    "primary capability " + spec.capability() + " exhausted; falling back to " + spec.fallback(),
                    "from", spec.capability(), "to", spec.fallback());
            return;
        }
        failTask(spec, state, kind, message);
    }

    /** Outputs of a rejected attempt are kept as evidence (never visible to other tasks). */
    private void keepAsEvidence(TaskSpec spec, TaskState state, AttemptResult rejected, String reason) {
        rejected.outputs().forEach((key, output) -> {
            Artifact artifact = run.artifacts().recordRejected(key, output.kind(), output.content(), spec.id(), state.attempt(),
                    rejected.inputs(), engine.clock.instant(), reason);
            log.append(EventType.ARTIFACT_REJECTED, spec.id(), state.attempt(), "kept rejected " + artifact.ref() + " as evidence",
                    "artifact", artifact.ref().toString());
        });
    }

    /** Sends the verified upstream work back with the failure evidence; false if any target is out of budget. */
    private boolean rework(TaskSpec verifier, TaskState verifierState, String message, List<String> details, List<String> suspects) {
        List<String> suspected = verifier.verifies().stream().filter(suspects::contains).toList();
        List<String> targets = suspected.isEmpty() ? verifier.verifies() : suspected;
        for (String target : targets) {
            TaskSpec targetSpec = run.plan().task(target);
            TaskState targetState = run.task(target);
            if (targetState.attempt() >= attemptBudget(targetSpec, targetState)) {
                log.append(EventType.REWORK_REQUESTED, verifier.id(), verifierState.attempt(),
                        "cannot rework " + target + ": attempt budget " + attemptBudget(targetSpec, targetState) + " exhausted",
                        "targets", targets, "possible", false);
                return false;
            }
        }
        log.append(EventType.REWORK_REQUESTED, verifier.id(), verifierState.attempt(),
                "sending " + targets + " back for rework: " + message, "targets", targets, "possible", true);
        Feedback feedback = new Feedback(verifier.id(), FailureKind.CODE_DEFECT, message, details);
        replanner.invalidate(targets, "rework requested by " + verifier.id(), feedback, false, Set.of(verifier.id()),
                Replanner.CAUSE_REWORK);
        verifierState.newGeneration();
        verifierState.status(TaskStatus.PENDING);
        countReplan("rework of " + targets);
        return true;
    }

    private void failTask(TaskSpec spec, TaskState state, FailureKind kind, String message) {
        state.status(TaskStatus.FAILED);
        state.detail(message);
        log.append(EventType.TASK_FAILED, spec.id(), state.attempt(), message, "kind", kind, "stage", spec.stage());
        requestHalt("task " + spec.id() + " failed (" + kind + "): " + message);
    }

    private void rollbackAttempt(AttemptResult result, String reason) {
        if (result == null || result.appliedChangeSetId() == null) {
            return;
        }
        applied(result.appliedChangeSetId()).ifPresent(applied -> {
            rollBack(applied, reason, CAUSE_ATTEMPT_REJECTED);
            // A restart must not find the rolled-back change set on the stack (its rollback would then be refused).
            checkpoint.accept(run);
        });
    }

    private void rollBack(AppliedChangeSet applied, String reason, String cause) {
        workspace.rollback(applied);
        run.removeApplied(applied.id());
        log.append(EventType.CHANGESET_ROLLED_BACK, applied.taskId(), applied.attempt(),
                "rolled back " + applied.id() + ": " + reason, "changeSet", applied.id(), "files", applied.paths(),
                "cause", cause, "workspaceHash", workspace.contentHash());
    }

    /**
     * A process that died mid-attempt leaves tasks marked RUNNING with no worker behind them. A change set the
     * orphaned attempt applied but never committed is rolled back, and the attempt does not count (so recorded
     * reasoning replays the same response); the task then simply runs again.
     */
    private void recoverOrphanedAttempts() {
        boolean recovered = false;
        for (TaskState state : run.activeTasks()) {
            if (state.status() != TaskStatus.RUNNING) {
                continue;
            }
            recovered = true;
            Optional<String> committed = run.artifacts().current(ArtifactKeys.changesOf(state.taskId()))
                    .map(changes -> changes.content().path("changeSet").asString());
            for (AppliedChangeSet applied : run.appliedChanges().reversed()) {
                if (applied.taskId().equals(state.taskId()) && !committed.map(applied.id()::equals).orElse(false)) {
                    rollBack(applied, "applied by an attempt orphaned by an interrupted process", CAUSE_ORPHANED);
                }
            }
            state.record(record(state, "unknown", engine.clock.instant(), AttemptRecord.Outcome.DISCARDED, null,
                    "orphaned by an interrupted process"));
            state.forgetOrphanedAttempt();
            log.append(EventType.ATTEMPT_DISCARDED, state.taskId(), state.attempt() + 1,
                    "attempt orphaned by an interrupted process; will run again");
        }
        if (recovered) {
            checkpoint.accept(run);
        }
    }

    /**
     * A snapshot can list change sets whose rollback already happened: the process may have stopped between
     * rollbacks and the next checkpoint (a plan adoption rolls back several batches under one checkpoint), or while
     * writing a change set whose record was made durable first. Such a stack cannot be compensated (the strict
     * rollback would refuse), so before anything else it is reconciled with the disk. Rollbacks happen newest
     * first and a whole change set at a time, so a change set can only be rolled back once every later one sharing
     * a file with it is; at most one change set - the one being rolled back or written at the stop - can be
     * interrupted part-way. An explanation of the disk is therefore a set of rolled-back change sets, closed under
     * "later change sets sharing a file", plus at most one interrupted member with no older member sharing its files:
     * <ul>
     *   <li>a file no rolled-back change set touches holds the post-image of the newest change set touching it;</li>
     *   <li>any other file holds the pre-image of the oldest rolled-back change set touching it, or, when that is
     *       the interrupted one, its post- or pre-image - the interrupted rollback (or write) is completed;</li>
     *   <li>a file matching no image of any change set touching it (a hand edit) constrains nothing, but no change
     *       set touching it is reconciled: it stays on the stack so the integrity gate or compensation fails closed.</li>
     * </ul>
     * Of the explanations, the one with the fewest rolled-back change sets wins (then one without an interrupted
     * member, then the newest interrupted one): it keeps the most committed work and writes the least. Only files
     * of the interrupted change set that hold its post-image are ever written. Owners are then brought in line: a
     * RUNNING owner is recovered as an orphan, a parked owner's decision finds no applied change, and a SUCCEEDED
     * owner whose committed change is gone is hard-invalidated.
     */
    private void reconcileAppliedChanges() {
        List<AppliedChangeSet> stack = run.appliedChanges();
        if (newestTouching(stack).values().stream().allMatch(file -> workspace.stateOf(file) == Workspace.FileState.POST_IMAGE)) {
            return;
        }
        List<Map<String, Standing>> standing = stack.stream().map(applied -> {
            Map<String, Standing> files = new HashMap<>();
            applied.files().forEach(file -> files.put(file.path(),
                    new Standing(workspace.stateOf(file) == Workspace.FileState.POST_IMAGE, workspace.holdsPreImage(file))));
            return files;
        }).toList();
        Optional<Recovery> recovery = explanation(stack, standing, -1);
        for (int interrupted = stack.size() - 1; interrupted >= 0; interrupted--) {
            Optional<Recovery> candidate = explanation(stack, standing, interrupted);
            if (candidate.isPresent() && (recovery.isEmpty() || candidate.get().rolledBack().size() < recovery.get().rolledBack().size())) {
                recovery = candidate;
            }
        }
        if (recovery.isEmpty() || recovery.get().rolledBack().isEmpty()) {
            return;
        }
        List<AppliedChangeSet> rolledBack = recovery.get().rolledBack();
        AppliedChangeSet interrupted = recovery.get().interrupted();
        List<String> completed = interrupted == null ? List.of() : workspace.completeRollback(interrupted);
        List<String> vanished = new ArrayList<>();
        for (AppliedChangeSet applied : rolledBack.reversed()) {
            List<String> restored = applied == interrupted ? completed : List.of();
            run.removeApplied(applied.id());
            TaskState owner = run.task(applied.taskId());
            log.append(EventType.CHANGESET_RECOVERED, applied.taskId(), applied.attempt(), "recovered " + applied.id() + ": "
                            + (restored.isEmpty() ? "it was already rolled back (or never written) before the restart"
                            : "completed its interrupted rollback or write (restored " + restored.size() + " file(s))"),
                    "changeSet", applied.id(), "files", applied.paths(), "diskTouched", !restored.isEmpty(),
                    "restored", restored, "ownerStatus", owner.status(), "workspaceHash", workspace.contentHash());
            boolean committed = run.artifacts().current(ArtifactKeys.changesOf(applied.taskId()))
                    .map(changes -> changes.content().path("changeSet").asString().equals(applied.id()))
                    .orElse(false);
            if (owner.status() == TaskStatus.SUCCEEDED && committed && run.plan().contains(owner.taskId())) {
                vanished.add(owner.taskId());
            }
        }
        if (!vanished.isEmpty()) {
            // After the stack is reconciled: invalidation may roll back change sets still on it.
            replanner.invalidate(vanished, "committed change set no longer in the workspace after a restart", null, true,
                    Set.of(), Replanner.CAUSE_INVALIDATION);
        }
        checkpoint.accept(run);
    }

    /** Whether a file of a stacked change set holds that change set's post-image and/or its pre-image. */
    private record Standing(boolean post, boolean pre) {

        boolean foreign() {
            return !post && !pre;
        }
    }

    /** Change sets found rolled back (stack order), and the one among them whose rollback or write was interrupted. */
    private record Recovery(List<AppliedChangeSet> rolledBack, AppliedChangeSet interrupted) {
    }

    /**
     * The smallest explanation of the disk (see reconciliation) in which {@code stack[interrupted]} is the
     * interrupted change set ({@code -1}: none is), found by adding only what the disk forces: the newest change
     * set touching a file not at its post-image, every later change set sharing a file with an added one, and the
     * next older change set touching a file not at the pre-image of the oldest added one touching it.
     */
    private static Optional<Recovery> explanation(List<AppliedChangeSet> stack, List<Map<String, Standing>> standing, int interrupted) {
        Map<String, List<Integer>> touching = new LinkedHashMap<>();
        for (int i = 0; i < stack.size(); i++) {
            for (String path : stack.get(i).paths()) {
                touching.computeIfAbsent(path, p -> new ArrayList<>()).add(i);
            }
        }
        Set<Integer> rolledBack = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, List<Integer>> entry : touching.entrySet()) {
                String path = entry.getKey();
                List<Integer> touchers = entry.getValue();
                boolean foreign = touchers.stream().allMatch(i -> standing.get(i).get(path).foreign());
                int oldest = 0;
                while (oldest < touchers.size() && !rolledBack.contains(touchers.get(oldest))) {
                    oldest++;
                }
                if (oldest == touchers.size()) {
                    if (!foreign && !standing.get(touchers.getLast()).get(path).post()) {
                        rollBackWithLaterSharers(stack, touching, touchers.getLast(), rolledBack);
                        grew = true;
                    }
                    continue;
                }
                Standing file = standing.get(touchers.get(oldest)).get(path);
                boolean isInterrupted = touchers.get(oldest) == interrupted;
                if (foreign || touchers.contains(interrupted) && touchers.indexOf(interrupted) > oldest) {
                    return Optional.empty();
                }
                if (isInterrupted ? file.post() || file.pre() : file.pre()) {
                    continue;
                }
                if (isInterrupted || oldest == 0) {
                    return Optional.empty();
                }
                rollBackWithLaterSharers(stack, touching, touchers.get(oldest - 1), rolledBack);
                grew = true;
            }
        }
        if (interrupted >= 0 && !rolledBack.contains(interrupted)) {
            return Optional.empty();
        }
        List<AppliedChangeSet> found = new ArrayList<>();
        for (int i = 0; i < stack.size(); i++) {
            if (rolledBack.contains(i)) {
                found.add(stack.get(i));
            }
        }
        return Optional.of(new Recovery(List.copyOf(found), interrupted >= 0 ? stack.get(interrupted) : null));
    }

    private static void rollBackWithLaterSharers(List<AppliedChangeSet> stack, Map<String, List<Integer>> touching, int index,
                                                 Set<Integer> rolledBack) {
        if (!rolledBack.add(index)) {
            return;
        }
        for (String path : stack.get(index).paths()) {
            touching.get(path).stream().filter(i -> i > index)
                    .forEach(later -> rollBackWithLaterSharers(stack, touching, later, rolledBack));
        }
    }

    private static Map<String, AppliedChangeSet.AppliedFile> newestTouching(List<AppliedChangeSet> changeSets) {
        Map<String, AppliedChangeSet.AppliedFile> files = new LinkedHashMap<>();
        changeSets.forEach(applied -> applied.files().forEach(file -> files.put(file.path(), file)));
        return files;
    }

    private void requestHalt(String reason) {
        if (haltReason == null) {
            haltReason = reason;
            log.append(EventType.SAFE_STOP_INITIATED, null, null, reason, "runningTasks", running);
        }
    }

    private void countReplan(String reason) {
        run.countReplan();
        if (run.replans() > engine.settings.maxReplans()) {
            requestHalt("re-plan budget of " + engine.settings.maxReplans() + " exhausted (" + reason + ")");
        }
    }

    // ---------------------------------------------------------------- human checkpoints

    private void park(String requestId, TaskSpec spec, TaskState state, TaskState.ParkedAt at, AttemptResult result, int gateIndex,
                      String gateName, GateResult.HumanNeed need, List<String> policyRules, String approvalFingerprint) {
        HumanRequest request = new HumanRequest(requestId, need.kind(), spec.id(), state.generation(),
                state.attempt(), gateName, need.title(), need.details(), need.questions(), policyRules,
                HumanRequest.Status.PENDING, null, engine.clock.instant(), null);
        run.addHumanRequest(request);
        String workspaceHash = at == TaskState.ParkedAt.GATE ? workspace.contentHash() : null;
        state.park(new TaskState.Parked(at, result, gateIndex, request.id(), approvalFingerprint, workspaceHash));
        log.append(EventType.HUMAN_INPUT_REQUESTED, spec.id(), state.attempt(), need.title(),
                "request", request.id(), "kind", need.kind(), "gate", gateName, "policyRules", policyRules);
        checkpoint.accept(run);
        Optional<HumanResponse> response = engine.human.respond(request);
        if (response.isEmpty()) {
            return;
        }
        Optional<String> problem = run.validate(request, response.get());
        if (problem.isPresent()) {
            log.append(EventType.HUMAN_INPUT_RECEIVED, spec.id(), state.attempt(), "ignored invalid response: " + problem.get(),
                    "request", request.id(), "ignored", true);
            return;
        }
        run.replaceHumanRequest(request.answered(response.get(), engine.clock.instant()));
        applyResponse(request.id());
    }

    private void applyResponse(String requestId) {
        HumanRequest request = run.humanRequest(requestId).orElseThrow();
        if (request.status() != HumanRequest.Status.ANSWERED) {
            return;
        }
        HumanResponse response = request.response();
        TaskState state = run.task(request.taskId());
        TaskSpec spec = run.plan().find(request.taskId()).orElse(null);
        TaskState.Parked parked = state.parked();
        if (spec == null || state.status() != TaskStatus.AWAITING_HUMAN || parked == null || !parked.requestId().equals(requestId)) {
            run.replaceHumanRequest(request.withStatus(HumanRequest.Status.CANCELLED));
            log.append(EventType.HUMAN_INPUT_CANCELLED, request.taskId(), request.attempt(),
                    "answer to " + requestId + " not applied: the checkpoint was withdrawn", "request", requestId,
                    "reason", "checkpoint withdrawn");
            return;
        }
        run.replaceHumanRequest(request.withStatus(HumanRequest.Status.APPLIED));
        log.append(EventType.HUMAN_INPUT_RECEIVED, request.taskId(), request.attempt(),
                response.decision() + " by " + response.reviewer() + (response.comment().isBlank() ? "" : ": " + response.comment()),
                "request", request.id(), "decision", response.decision(), "reviewer", response.reviewer(),
                "answers", response.answers().isEmpty() ? null : response.answers(), "changeTarget", response.changeTarget());
        state.clearParked();
        state.status(TaskStatus.RUNNING);
        switch (parked.at()) {
            case CHANGE_APPROVAL -> {
                switch (response.decision()) {
                    case APPROVE -> {
                        publishApproval(request, response, parked);
                        continueAttempt(spec, state, parked.result(), 0, parked.approvalFingerprint());
                    }
                    case REQUEST_CHANGES -> failAttempt(spec, state, parked.result(), FailureKind.CHANGES_REQUESTED,
                            "reviewer requested changes: " + response.comment(), List.of(response.comment()), List.of());
                    default -> failAttempt(spec, state, parked.result(), FailureKind.REJECTED,
                            "reviewer rejected the change set: " + response.comment(), List.of(response.comment()), List.of());
                }
            }
            case GATE -> {
                switch (response.decision()) {
                    case APPROVE -> approveGate(spec, state, parked, request, response);
                    case REQUEST_CHANGES -> requestChanges(spec, state, parked, response);
                    default -> {
                        rollbackAttempt(parked.result(), "rejected at " + request.gate());
                        failAttempt(spec, state, parked.result(), FailureKind.REJECTED,
                                "reviewer rejected at " + request.gate() + ": " + response.comment(), List.of(response.comment()), List.of());
                    }
                }
            }
            case CLARIFICATION -> {
                if (response.decision() != HumanResponse.Decision.ANSWER || response.answers().isEmpty()) {
                    failAttempt(spec, state, parked.result(), FailureKind.REJECTED,
                            "clarification declined: " + response.comment(), List.of(), List.of());
                    return;
                }
                publishClarifications(request, response);
                state.record(record(state, capability(spec, state).name(), parked.result().startedAt(),
                        AttemptRecord.Outcome.DISCARDED, null, "needed clarification; answered by " + response.reviewer()));
                state.newGeneration();
                state.status(TaskStatus.PENDING);
                log.append(EventType.TASK_INVALIDATED, spec.id(), null,
                        "re-analysing with clarified requirement", "generation", state.generation(), "newGeneration", true);
                countReplan("clarification answered for " + spec.id());
            }
        }
    }

    /** A gate approval only counts for the workspace state the reviewer was shown evidence about. */
    private void approveGate(TaskSpec spec, TaskState state, TaskState.Parked parked, HumanRequest request, HumanResponse response) {
        String now = workspace.contentHash();
        if (parked.workspaceHash() != null && !parked.workspaceHash().equals(now)) {
            rollbackAttempt(parked.result(), "workspace changed while waiting for " + request.gate());
            failAttempt(spec, state, parked.result(), FailureKind.CONFLICT,
                    "workspace changed while waiting for " + request.gate() + " approval; the evidence is re-assessed",
                    List.of("approved state " + parked.workspaceHash(), "current state " + now), List.of());
            return;
        }
        publishApproval(request, response, parked);
        continueAttempt(spec, state, parked.result(), parked.gateIndex() + 1, null);
    }

    private void requestChanges(TaskSpec spec, TaskState state, TaskState.Parked parked, HumanResponse response) {
        String target = response.changeTarget() == null ? spec.id() : response.changeTarget();
        Feedback feedback = new Feedback("human:" + response.reviewer(), FailureKind.CHANGES_REQUESTED,
                response.comment(), List.of(response.comment()));
        if (target.equals(spec.id())) {
            rollbackAttempt(parked.result(), "changes requested");
            failAttempt(spec, state, parked.result(), FailureKind.CHANGES_REQUESTED,
                    "reviewer requested changes: " + response.comment(), List.of(response.comment()), List.of());
            return;
        }
        rollbackAttempt(parked.result(), "changes requested upstream in " + target);
        state.record(record(state, capability(spec, state).name(), parked.result().startedAt(), AttemptRecord.Outcome.DISCARDED,
                FailureKind.CHANGES_REQUESTED, "reviewer requested changes in " + target));
        log.append(EventType.REWORK_REQUESTED, spec.id(), state.attempt(),
                "reviewer requested changes in upstream task " + target + ": " + response.comment(),
                "targets", List.of(target), "requestedBy", "human:" + response.reviewer(), "possible", true);
        replanner.invalidate(List.of(target), "reviewer requested changes: " + response.comment(), feedback, true,
                Set.of(spec.id()), Replanner.CAUSE_INVALIDATION);
        state.newGeneration();
        state.status(TaskStatus.PENDING);
        countReplan("change request on " + target);
    }

    /** Approvals become artifacts so later reviews (and lineage queries) can prove which human allowed what. */
    private void publishApproval(HumanRequest request, HumanResponse response, TaskState.Parked parked) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", request.id());
        content.put("taskId", request.taskId());
        content.put("kind", request.kind().name());
        content.put("gate", request.gate() == null ? "" : request.gate());
        content.put("rules", request.policyRules());
        content.put("subject", request.title());
        content.put("reviewer", response.reviewer());
        content.put("comment", response.comment());
        content.put("changeFingerprint", parked.approvalFingerprint() == null ? "" : parked.approvalFingerprint());
        content.put("workspaceHash", parked.workspaceHash() == null ? "" : parked.workspaceHash());
        run.artifacts().publish("approval/" + request.id(), "approval", Json.tree(content), "human:" + response.reviewer(),
                0, List.of(), engine.clock.instant());
    }

    private void publishClarifications(HumanRequest request, HumanResponse response) {
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, String> answers = new LinkedHashMap<>();
        run.artifacts().current(ArtifactKeys.CLARIFICATIONS).ifPresent(previous ->
                previous.content().path("answers").properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString())));
        answers.putAll(response.answers());
        content.put("answers", answers);
        content.put("questions", request.questions());
        content.put("answeredBy", response.reviewer());
        ArtifactStore.Publication publication = run.artifacts().publish(ArtifactKeys.CLARIFICATIONS, "clarifications",
                Json.tree(content), "human:" + response.reviewer(), 0, List.of(), engine.clock.instant());
        log.append(EventType.ARTIFACT_PUBLISHED, request.taskId(), null, "published " + publication.artifact().ref()
                + " (human answers)", "artifact", publication.artifact().ref().toString(), "kind", "clarifications");
    }

    // ---------------------------------------------------------------- run outcomes

    private void pause() {
        List<String> pending = run.pendingHumanRequests().stream().map(HumanRequest::id).toList();
        run.status(RunStatus.AWAITING_HUMAN, "waiting for human input: " + pending);
        log.append(EventType.RUN_PAUSED, null, null, "paused safely; waiting for " + pending, "requests", pending);
    }

    private void complete() {
        boolean released = run.plan().tasks().stream()
                .anyMatch(t -> engine.capabilities.get(t.capability()).role() == Capability.Role.RELEASE);
        run.verdict(released ? "READY" : null);
        run.status(RunStatus.COMPLETED, released ? "release approved" : "all tasks succeeded");
        log.append(EventType.RUN_COMPLETED, null, null, "run completed" + (released ? "; release verdict READY" : ""),
                "verdict", run.verdict());
    }

    /**
     * Safe stop: cancel what has not run, block what depended on failed work, then compensate every applied
     * change set in reverse order so the workspace returns to its verified baseline.
     */
    private void safeStop() {
        try {
            // Answers recorded but not yet applied (e.g. behind an earlier answer that stopped the run) will never
            // take effect either, so they are cancelled too rather than left looking pending.
            for (HumanRequest request : run.humanRequests()) {
                boolean answered = request.status() == HumanRequest.Status.ANSWERED;
                if (request.status() != HumanRequest.Status.PENDING && !answered) {
                    continue;
                }
                run.replaceHumanRequest(request.withStatus(HumanRequest.Status.CANCELLED));
                log.append(EventType.HUMAN_INPUT_CANCELLED, request.taskId(), request.attempt(),
                        (answered ? "answer to " + request.id() + " not applied: " : "checkpoint " + request.id() + " ")
                                + "cancelled by safe stop", "request", request.id(), "reason", "safe stop");
            }
            for (TaskSpec spec : run.plan().topologicalOrder()) {
                TaskState state = run.task(spec.id());
                if (state.status().isTerminal()) {
                    continue;
                }
                state.clearParked();
                boolean blocked = run.plan().ancestors(spec.id()).stream()
                        .anyMatch(a -> run.task(a).status() == TaskStatus.FAILED || run.task(a).status() == TaskStatus.BLOCKED);
                state.status(blocked ? TaskStatus.BLOCKED : TaskStatus.CANCELLED);
                state.detail(blocked ? "prerequisite failed" : "not started: safe stop");
                log.append(blocked ? EventType.TASK_BLOCKED : EventType.TASK_CANCELLED, spec.id(), null, state.detail());
            }
            String compensation = compensate();
            run.verdict("NOT_READY");
            run.status(RunStatus.HALTED, haltReason + compensation);
            log.append(EventType.RUN_HALTED, null, null, "halted safely: " + haltReason + compensation, "verdict", "NOT_READY");
        } catch (RuntimeException e) {
            run.verdict("NOT_READY");
            run.status(RunStatus.HALTED, haltReason + "; safe stop incomplete (" + WorkflowEngine.describe(e)
                    + ") - manual intervention required");
            log.append(EventType.RUN_HALTED, null, null, "halted with incomplete safe stop: " + WorkflowEngine.describe(e),
                    "verdict", "NOT_READY");
        }
    }

    private String compensate() {
        List<AppliedChangeSet> applied = run.appliedChanges();
        if (!applied.isEmpty()) {
            run.artifacts().publish(ArtifactKeys.ABANDONED_CHANGES, "patch", Json.tree(Map.of(
                            "reason", haltReason,
                            "changeSets", applied.stream().map(AppliedChangeSet::id).toList(),
                            "diff", workspace.unifiedDiff(run.setting("patchPrefix", "")))),
                    "engine", 0, List.of(), engine.clock.instant());
        }
        List<AppliedChangeSet> remaining = new ArrayList<>(applied.reversed());
        while (!remaining.isEmpty()) {
            AppliedChangeSet changeSet = remaining.getFirst();
            try {
                rollBack(changeSet, "compensated during safe stop", CAUSE_SAFE_STOP);
            } catch (WorkspaceException e) {
                log.append(EventType.WORKSPACE_RESTORED, null, null, "compensation failed: " + e.getMessage(), "verified", false);
                return "; COMPENSATION FAILED (" + e.getMessage() + ") - manual intervention required for "
                        + remaining.stream().map(AppliedChangeSet::id).toList();
            }
            remaining.removeFirst();
            TaskState state = run.task(changeSet.taskId());
            if (state.status() == TaskStatus.SUCCEEDED) {
                state.status(TaskStatus.ROLLED_BACK);
            }
            run.artifacts().retract(ArtifactKeys.changesOf(changeSet.taskId()), "compensated during safe stop");
            checkpoint.accept(run);
        }
        boolean restored = workspace.contentHash().equals(workspace.baselineHash());
        log.append(EventType.WORKSPACE_RESTORED, null, null,
                restored ? "workspace verified identical to baseline" : "workspace differs from baseline after compensation",
                "verified", restored, "workspaceHash", workspace.contentHash(), "baselineHash", workspace.baselineHash());
        if (!restored) {
            return "; workspace differs from baseline after compensation - manual intervention required";
        }
        return applied.isEmpty() ? "" : "; all changes compensated, workspace restored to baseline";
    }

    // ---------------------------------------------------------------- helpers

    private GateResult evaluate(String gateName, GateContext context) {
        try {
            return gate(gateName).evaluate(context);
        } catch (RuntimeException e) {
            return new GateResult(GateResult.Verdict.FAIL, "gate error: " + WorkflowEngine.describe(e), List.of(),
                    WorkflowEngine.classify(e), null, List.of());
        }
    }

    private Optional<AppliedChangeSet> applied(String changeSetId) {
        return run.appliedChanges().stream().filter(a -> a.id().equals(changeSetId)).findFirst();
    }

    private static int sequenceOf(String changeSetId) {
        return Integer.parseInt(changeSetId.substring(changeSetId.lastIndexOf('-') + 1));
    }

    private boolean changesWorkspace(String taskId) {
        return run.plan().find(taskId)
                .map(spec -> engine.capabilities.get(spec.capability()))
                .map(Capability::proposesChanges)
                .orElse(false);
    }

    private Set<String> anticipatedPaths() {
        return run.artifacts().current(ArtifactKeys.IMPACT_ANALYSIS)
                .map(artifact -> artifact.content().path("anticipatedPaths"))
                .filter(JsonNode::isArray)
                .map(node -> {
                    Set<String> paths = new LinkedHashSet<>();
                    node.forEach(path -> paths.add(path.asString()));
                    return paths;
                })
                .orElse(null);
    }

    private List<String> exitGates(TaskSpec spec, TaskState state) {
        Capability capability = capability(spec, state);
        return state.onFallback() ? capability.exitGates() : union(capability.exitGates(), spec.exitGates());
    }

    private int attemptBudget(TaskSpec spec, TaskState state) {
        return state.onFallback() ? engine.settings.fallbackAttempts() : Math.max(1, spec.maxAttempts());
    }

    private Capability capability(TaskSpec spec, TaskState state) {
        String name = state.onFallback() && spec.fallback() != null ? spec.fallback() : spec.capability();
        Capability capability = engine.capabilities.get(name);
        if (capability == null) {
            throw new IllegalStateException("no capability registered for '" + name + "'");
        }
        return capability;
    }

    private Gate gate(String name) {
        Gate gate = engine.gates.get(name);
        if (gate == null) {
            throw new IllegalStateException("no gate registered for '" + name + "'");
        }
        return gate;
    }

    private AttemptRecord record(TaskState state, String capability, Instant startedAt, AttemptRecord.Outcome outcome,
                                 FailureKind kind, String message) {
        return new AttemptRecord(state.generation(), state.attempt(), capability, state.onFallback(), startedAt,
                engine.clock.instant(), outcome, kind, message);
    }

    private AttemptRecord attemptRecord(WorkerOutcome outcome, AttemptRecord.Outcome result, String message) {
        return new AttemptRecord(outcome.generation(), outcome.attempt(), outcome.capability(), outcome.fallback(),
                outcome.startedAt(), engine.clock.instant(), result, null, message);
    }

    private static int maxWidth(WorkflowPlan plan) {
        Map<String, Integer> depth = new HashMap<>();
        for (TaskSpec task : plan.topologicalOrder()) {
            depth.put(task.id(), task.dependsOn().stream().mapToInt(depth::get).max().orElse(-1) + 1);
        }
        return depth.values().stream().collect(Collectors.groupingBy(d -> d, Collectors.counting()))
                .values().stream().mapToInt(Long::intValue).max().orElse(0);
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }
}
