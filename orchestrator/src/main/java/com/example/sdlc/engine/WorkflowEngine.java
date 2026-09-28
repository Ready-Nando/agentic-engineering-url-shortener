package com.example.sdlc.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.example.sdlc.human.HumanGateway;
import com.example.sdlc.plan.PlanRejectedException;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.workspace.Workspace;
import com.example.sdlc.workspace.WorkspaceException;

import tools.jackson.core.JacksonException;

/**
 * Deterministic orchestration core: the configuration (capabilities, gates, policy, reviewer, limits) shared by
 * every run. Each call to {@link #execute} drives one {@link RunExecution}: a single coordinator thread owns
 * all state transitions while handlers run concurrently on a worker pool.
 *
 * <p>Control flow per task: PENDING -(all ancestors succeeded + entry gates)-> READY -> RUNNING -> result ->
 * [policy on proposed changes: deny | park for approval | apply] -> exit gates in order [pass | fail -> retry,
 * fallback, rework or fail | park for human] -> commit (publish artifacts, propagate changes selectively).
 * Any unrecoverable failure - including an unexpected error in the engine itself - triggers a safe stop: no
 * new work, running work drains, applied changes are compensated in reverse order and the workspace is
 * verified against its baseline.
 */
public final class WorkflowEngine {

    public static final String BASELINE_TASK = "baseline";
    public static final int MAX_ATTEMPTS_CAP = 3;

    public record Settings(int parallelism, int maxReplans, int fallbackAttempts) {
        public static Settings defaults() {
            return new Settings(4, 8, 2);
        }
    }

    final Map<String, Capability> capabilities;
    final Map<String, Gate> gates;
    final ChangePolicy policy;
    final HumanGateway human;
    final PlanValidator planValidator;
    final Clock clock;
    final Settings settings;

    public WorkflowEngine(Collection<Capability> capabilities, Collection<Gate> gates, ChangePolicy policy,
                          HumanGateway human, Clock clock, Settings settings) {
        this.capabilities = capabilities.stream().collect(Collectors.toMap(Capability::name, c -> c, (a, b) -> a, LinkedHashMap::new));
        this.gates = gates.stream().collect(Collectors.toMap(Gate::name, g -> g, (a, b) -> a, LinkedHashMap::new));
        this.policy = policy;
        this.human = human;
        this.clock = clock;
        this.settings = settings;
        this.planValidator = new PlanValidator(this.capabilities, this.gates.keySet(), MAX_ATTEMPTS_CAP);
    }

    /**
     * Runs (or resumes) until the run completes, halts safely, or pauses for a human.
     *
     * @param checkpoint called with the run whenever durable state should be persisted
     */
    public RunStatus execute(WorkflowRun run, Workspace workspace, EventLog log, Consumer<WorkflowRun> checkpoint) {
        return new RunExecution(this, run, workspace, log, checkpoint).execute();
    }

    /**
     * Human-initiated safe stop of a run that is not currently executing (e.g. paused for a checkpoint):
     * pending checkpoints are cancelled and every applied change set is compensated.
     */
    public RunStatus stop(WorkflowRun run, Workspace workspace, EventLog log, Consumer<WorkflowRun> checkpoint, String reason) {
        return new RunExecution(this, run, workspace, log, checkpoint).stop(reason);
    }

    static FailureKind classify(Throwable error) {
        if (error instanceof TaskFailure failure) {
            return failure.kind();
        }
        if (error instanceof PlanRejectedException) {
            return FailureKind.INVALID_OUTPUT;
        }
        if (error instanceof WorkspaceException workspaceError) {
            return kindOf(workspaceError);
        }
        if (error instanceof JacksonException) {
            return FailureKind.INVALID_OUTPUT;
        }
        if (error instanceof UncheckedIOException || error instanceof IOException || error instanceof TimeoutException) {
            return FailureKind.TRANSIENT;
        }
        return FailureKind.FATAL;
    }

    static FailureKind kindOf(WorkspaceException error) {
        return error.reason() == WorkspaceException.Reason.CONFLICT ? FailureKind.CONFLICT : FailureKind.INVALID_OUTPUT;
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        return error instanceof TaskFailure ? message : error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
