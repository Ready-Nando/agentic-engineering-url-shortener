package com.example.sdlc.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.Workspace;

/**
 * Selective invalidation. Given tasks that must be redone (rework, human change request, changed plan) or
 * artifact versions that were superseded, it finds exactly the work derived from them via recorded lineage,
 * compensates workspace side effects in reverse application order, and resets that work - leaving every
 * unrelated completed task untouched.
 *
 * <p>Two propagation modes keep the blast radius minimal:
 * <ul>
 *   <li>Tasks that changed the workspace are <em>hard</em>-invalidated: their change set is rolled back and
 *       their outputs retracted immediately, so everything that consumed them is invalidated too.</li>
 *   <li>Pure tasks (artifacts only) keep their outputs while they re-run ("early cutoff"): consumers are only
 *       invalidated if the re-run actually produces different content.</li>
 * </ul>
 */
final class Replanner {

    private final WorkflowRun run;
    private final Workspace workspace;
    private final EventLog log;
    private final Predicate<String> changesWorkspace;

    Replanner(WorkflowRun run, Workspace workspace, EventLog log, Predicate<String> changesWorkspace) {
        this.run = run;
        this.workspace = workspace;
        this.log = log;
        this.changesWorkspace = changesWorkspace;
    }

    /** Why change sets are rolled back; recorded on every rollback event so metrics can tell them apart. */
    static final String CAUSE_REWORK = "rework";
    static final String CAUSE_INVALIDATION = "invalidation";

    /** Invalidates the consumers of superseded or retracted artifact versions. */
    Set<String> propagate(Collection<ArtifactRef> changed, String reason) {
        Set<String> consumers = consumersOf(changed);
        if (consumers.isEmpty()) {
            return consumers;
        }
        return invalidate(consumers, reason, null, true, Set.of(), CAUSE_INVALIDATION);
    }

    /**
     * @param roots               tasks that must run again
     * @param rootFeedback        feedback for the roots' next attempt (may be null)
     * @param newGenerationForRoots false for rework (roots continue their attempt budget), true when roots must
     *                            re-run because their inputs or instructions changed
     * @param exclude             tasks the caller resets itself (e.g. the verifier that asked for rework)
     * @param cause               recorded on rollback events ({@link #CAUSE_REWORK} or {@link #CAUSE_INVALIDATION})
     * @return every task that was reset
     * @throws com.example.sdlc.workspace.WorkspaceException if a change set can no longer be rolled back; the
     *         engine turns that into a safe stop (already rolled back change sets stay consistent)
     */
    Set<String> invalidate(Collection<String> roots, String reason, Feedback rootFeedback, boolean newGenerationForRoots,
                           Set<String> exclude, String cause) {
        Set<String> affected = new LinkedHashSet<>();
        Set<String> hard = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String taskId = queue.poll();
            if (!affected.add(taskId)) {
                continue;
            }
            if (changesWorkspace.test(taskId) && hasAppliedChanges(taskId)) {
                hard.add(taskId);
                List<ArtifactRef> outputs = run.artifacts().producedBy(taskId).stream().map(Artifact::ref).toList();
                consumersOf(outputs).stream().filter(t -> !affected.contains(t)).forEach(queue::add);
            }
        }

        rollBack(hard, reason, cause);
        for (String taskId : hard) {
            for (Artifact output : run.artifacts().producedBy(taskId)) {
                run.artifacts().retract(output.key(), reason);
                log.append(EventType.ARTIFACT_RETRACTED, taskId, null, "retracted " + output.ref() + ": " + reason,
                        "artifact", output.ref().toString());
            }
        }
        for (String taskId : affected) {
            if (exclude.contains(taskId)) {
                continue;
            }
            boolean root = roots.contains(taskId);
            reset(run.task(taskId), root ? newGenerationForRoots : true, root ? rootFeedback : null,
                    root ? reason : "consumed output of invalidated work (" + reason + ")");
        }
        return affected;
    }

    /** Adopts a new plan version, preserving tasks whose spec is unchanged. */
    void adopt(WorkflowPlan next, List<String> notes) {
        WorkflowPlan previous = run.plan();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> preserved = new ArrayList<>();
        for (TaskSpec spec : next.tasks()) {
            TaskSpec before = previous.find(spec.id()).orElse(null);
            if (before == null) {
                added.add(spec.id());
            } else if (before.equals(spec)) {
                preserved.add(spec.id());
            } else {
                changed.add(spec.id());
            }
        }
        for (TaskSpec spec : previous.tasks()) {
            if (!next.contains(spec.id())) {
                removed.add(spec.id());
            }
        }
        if (!removed.isEmpty()) {
            invalidate(removed, "removed by plan v" + next.version(), null, true, Set.of(), CAUSE_INVALIDATION);
            for (String taskId : removed) {
                TaskState state = run.task(taskId);
                state.status(TaskStatus.CANCELLED);
                state.detail("removed by plan v" + next.version());
                log.append(EventType.TASK_CANCELLED, taskId, null, "removed by plan v" + next.version());
            }
        }
        run.adoptPlan(next);
        for (String taskId : added) {
            run.task(taskId).status(TaskStatus.PENDING);
        }
        if (!changed.isEmpty()) {
            invalidate(changed, "task definition changed in plan v" + next.version(), null, true, Set.of(), CAUSE_INVALIDATION);
        }
        log.append(EventType.PLAN_REVISED, null, null,
                "plan v" + next.version() + ": +" + added.size() + " added, " + changed.size() + " changed, "
                        + removed.size() + " removed, " + preserved.size() + " preserved",
                "version", next.version(), "added", added, "changed", changed, "removed", removed,
                "preserved", preserved, "notes", notes, "rationale", next.rationale());
    }

    private void reset(TaskState state, boolean newGeneration, Feedback feedback, String reason) {
        if (state.parked() != null) {
            run.humanRequest(state.parked().requestId())
                    .filter(r -> r.status() == HumanRequest.Status.PENDING || r.status() == HumanRequest.Status.ANSWERED)
                    .ifPresent(r -> {
                        run.replaceHumanRequest(r.withStatus(HumanRequest.Status.CANCELLED));
                        log.append(EventType.HUMAN_INPUT_CANCELLED, state.taskId(), r.attempt(),
                                "checkpoint " + r.id() + " withdrawn: " + reason, "request", r.id(), "reason", reason);
                    });
            state.clearParked();
        }
        // A running attempt is left to finish; bumping the generation makes the engine discard its result.
        boolean running = state.status() == TaskStatus.RUNNING;
        if (newGeneration || running) {
            state.newGeneration();
        }
        if (feedback != null) {
            state.addFeedback(feedback);
        }
        if (!running) {
            state.status(TaskStatus.PENDING);
        }
        state.detail(reason);
        log.append(EventType.TASK_INVALIDATED, state.taskId(), null, reason,
                "generation", state.generation(), "newGeneration", newGeneration);
    }

    private void rollBack(Set<String> taskIds, String reason, String cause) {
        List<AppliedChangeSet> toRollBack = run.appliedChanges().stream()
                .filter(applied -> taskIds.contains(applied.taskId()))
                .toList()
                .reversed();
        for (AppliedChangeSet applied : toRollBack) {
            workspace.rollback(applied);
            run.removeApplied(applied.id());
            // Retract right away so the run never describes a change set that is no longer in the workspace.
            run.artifacts().retract(ArtifactKeys.changesOf(applied.taskId()), reason);
            log.append(EventType.CHANGESET_ROLLED_BACK, applied.taskId(), applied.attempt(),
                    "rolled back " + applied.id() + " (" + applied.files().size() + " files): " + reason,
                    "changeSet", applied.id(), "files", applied.paths(), "cause", cause, "workspaceHash", workspace.contentHash());
        }
    }

    private boolean hasAppliedChanges(String taskId) {
        return run.appliedChanges().stream().anyMatch(applied -> applied.taskId().equals(taskId))
                || run.artifacts().current(ArtifactKeys.changesOf(taskId)).isPresent();
    }

    /** Active tasks whose committed (or parked) result was derived from any of {@code refs}. */
    Set<String> consumersOf(Collection<ArtifactRef> refs) {
        Set<ArtifactRef> wanted = Set.copyOf(refs);
        Set<String> consumers = new LinkedHashSet<>();
        for (TaskState state : run.activeTasks()) {
            List<ArtifactRef> inputs = switch (state.status()) {
                case SUCCEEDED -> state.inputs();
                case AWAITING_HUMAN -> state.parked() == null ? List.of() : state.parked().result().inputs();
                default -> List.of();
            };
            if (inputs.stream().anyMatch(wanted::contains)) {
                consumers.add(state.taskId());
            }
        }
        return consumers;
    }
}
