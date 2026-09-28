package com.example.sdlc.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.gates.StandardGates;
import com.example.sdlc.human.HumanGateway;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.workspace.ChangeSet;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.Workspace;

/** Builds an engine around synthetic capabilities so engine semantics can be tested in isolation. */
final class EngineHarness {

    /** How long a test handler waits for a coordination signal before giving up (fails the attempt, never hangs). */
    static final long SIGNAL_TIMEOUT_SECONDS = 10;

    final Workspace workspace;
    final List<Capability> capabilities = new ArrayList<>();
    final List<Gate> gates = new ArrayList<>();
    final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
    HumanGateway human = HumanGateway.DEFERRED;
    WorkflowEngine.Settings settings = new WorkflowEngine.Settings(4, 6, 2);
    EventLog log;

    EngineHarness(Path tempDir) throws IOException {
        this(tempDir, Map.of());
    }

    /** @param extraFiles additional baseline files of the target module (relative path to content) */
    EngineHarness(Path tempDir, Map<String, String> extraFiles) throws IOException {
        Path module = tempDir.resolve("module");
        Files.createDirectories(module.resolve("src/main/java/demo"));
        Files.createDirectories(module.resolve("src/main/resources/db/migration"));
        Files.writeString(module.resolve("src/main/java/demo/App.java"), "package demo;\n\nclass App {\n}\n");
        Files.writeString(module.resolve("src/main/resources/db/migration/V1__init.sql"), "CREATE TABLE t (id BIGINT);\n");
        for (Map.Entry<String, String> file : extraFiles.entrySet()) {
            Path target = module.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        workspace = Workspace.create(module, Map.of(), tempDir.resolve("run"));
    }

    EngineHarness capability(String name, TaskHandler handler) {
        return capability(name, List.of(), null, handler);
    }

    EngineHarness capability(String name, List<String> exitGates, String fallback, TaskHandler handler) {
        return capability(name, List.of(), exitGates, fallback, handler);
    }

    EngineHarness capability(String name, List<String> entryGates, List<String> exitGates, String fallback, TaskHandler handler) {
        capabilities.add(new Capability(name, Stage.IMPLEMENTATION, Capability.Role.WORK, true, false, List.of(), false, 1,
                entryGates, exitGates, fallback, counting(handler)));
        return this;
    }

    EngineHarness changeCapability(String name, TaskHandler handler) {
        capabilities.add(new Capability(name, Stage.IMPLEMENTATION, Capability.Role.WORK, true, true, List.of("src/"), false, 1,
                List.of(), List.of(), null, counting(handler)));
        return this;
    }

    /** A plannable capability with an explicit governance role (verification, security review, release). */
    EngineHarness roleCapability(String name, Capability.Role role, List<String> exitGates, TaskHandler handler) {
        capabilities.add(new Capability(name, Stage.VALIDATION, role, true, false, List.of(), false, 1,
                List.of(), exitGates, null, counting(handler)));
        return this;
    }

    /** A bootstrap-only capability whose {@code plan/proposal} output revises the workflow graph. */
    EngineHarness plannerCapability(String name, TaskHandler handler) {
        capabilities.add(new Capability(name, Stage.PLANNING, Capability.Role.WORK, false, false, List.of(), true, 2,
                List.of(), List.of(), null, counting(handler)));
        return this;
    }

    EngineHarness gate(String name, Function<GateContext, GateResult> evaluation) {
        gates.add(Gate.of(name, evaluation));
        return this;
    }

    /** Registers one of the production gates (they do not need an indexer for the gates used here). */
    EngineHarness standardGate(String name) {
        gates.add(new StandardGates(null, null).all().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow());
        return this;
    }

    int executions(String taskId) {
        return executions.getOrDefault(taskId, new AtomicInteger()).get();
    }

    WorkflowRun newRun(TaskSpec... tasks) {
        WorkflowRun run = new WorkflowRun("run-1", "test", "test run", "requirement", Instant.now(),
                WorkflowPlan.of(1, List.of(tasks), "test", "test"), Map.of());
        log = new EventLog(run.id(), Clock.systemUTC(), List.of());
        return run;
    }

    WorkflowEngine engine() {
        return new WorkflowEngine(capabilities, gates, new ChangePolicy(), human, Clock.systemUTC(), settings);
    }

    RunStatus execute(WorkflowRun run) {
        if (log == null) {
            log = new EventLog(run.id(), Clock.systemUTC(), List.of());
        }
        return engine().execute(run, workspace, log, r -> { });
    }

    List<EventType> eventTypes(String taskId) {
        return log.events().stream().filter(e -> taskId.equals(e.taskId())).map(ExecutionEvent::type).toList();
    }

    long count(EventType type) {
        return log.events().stream().filter(e -> e.type() == type).count();
    }

    List<ExecutionEvent> events(EventType type, String taskId) {
        return log.events().stream().filter(e -> e.type() == type && taskId.equals(e.taskId())).toList();
    }

    /** A latch released (on the coordinator thread) by the first event matching {@code trigger}. */
    CountDownLatch signalOn(Predicate<ExecutionEvent> trigger) {
        CountDownLatch latch = new CountDownLatch(1);
        log.subscribe(event -> {
            if (trigger.test(event)) {
                latch.countDown();
            }
        });
        return latch;
    }

    /** Blocks a handler until {@code latch} is released; fails the attempt instead of hanging the test. */
    static void await(CountDownLatch latch, String what) throws InterruptedException {
        if (!latch.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for " + what);
        }
    }

    static TaskSpec task(String id, String capability, String... dependsOn) {
        return TaskSpec.of(id, Stage.IMPLEMENTATION, capability, List.of(dependsOn));
    }

    /** A change task allowed to touch {@code src/main/**} with the given attempt budget. */
    static TaskSpec changeTask(String id, String capability, int maxAttempts, String... dependsOn) {
        return new TaskSpec(id, id, Stage.IMPLEMENTATION, capability, List.of(dependsOn), "", List.of(),
                List.of("src/main/**"), List.of(), List.of(), List.of(), maxAttempts, null);
    }

    /** A verification task that sends {@code verifies} back for rework on a code defect. */
    static TaskSpec verifyTask(String id, String capability, List<String> verifies, String... dependsOn) {
        return new TaskSpec(id, id, Stage.TESTING, capability, List.of(dependsOn), "", List.of(), List.of(), verifies,
                List.of(), List.of(), 1, null);
    }

    static TaskResult output(String key, Object value) {
        return TaskResult.of("produced " + key, key, OutputArtifact.of("test", Map.of("value", value)));
    }

    static TaskResult change(String summary, FileChange... changes) {
        return TaskResult.withChanges(summary, new ChangeSet(summary, "LOW", List.of(changes), Map.of()), Map.of());
    }

    /** Sequence number of the first event of {@code type} for {@code taskId}. */
    long firstSeq(EventType type, String taskId) {
        return events(type, taskId).stream().mapToLong(ExecutionEvent::seq).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " for " + taskId));
    }

    /** Sequence number of the last event of {@code type} for {@code taskId}. */
    long lastSeq(EventType type, String taskId) {
        return events(type, taskId).stream().mapToLong(ExecutionEvent::seq).max()
                .orElseThrow(() -> new AssertionError("no " + type + " for " + taskId));
    }

    /**
     * Sequence numbers at which {@code taskId} started while {@code ancestor} was invalidated and had not yet
     * succeeded again; a task must never run on top of upstream work that is being redone.
     */
    List<Long> startsWhileRedoing(String taskId, String ancestor) {
        List<Long> starts = new ArrayList<>();
        boolean redoing = false;
        for (ExecutionEvent event : log.events()) {
            if (ancestor.equals(event.taskId()) && event.type() == EventType.TASK_INVALIDATED) {
                redoing = true;
            } else if (ancestor.equals(event.taskId()) && event.type() == EventType.TASK_SUCCEEDED) {
                redoing = false;
            } else if (redoing && taskId.equals(event.taskId()) && event.type() == EventType.TASK_STARTED) {
                starts.add(event.seq());
            }
        }
        return starts;
    }

    static List<String> refs(List<Artifact> artifacts) {
        return artifacts.stream().map(a -> a.ref().toString()).toList();
    }

    private TaskHandler counting(TaskHandler delegate) {
        return context -> {
            executions.computeIfAbsent(context.task().id(), k -> new AtomicInteger()).incrementAndGet();
            return delegate.execute(context);
        };
    }

    // ---------------------------------------------------------------- persistence helpers

    RunStatus execute(WorkflowRun run, Consumer<WorkflowRun> checkpoint) {
        if (log == null) {
            log = new EventLog(run.id(), Clock.systemUTC(), List.of());
        }
        return engine().execute(run, workspace, log, checkpoint);
    }

    /** Human-initiated safe stop through a fresh engine, as {@code sdlc cancel} does. */
    RunStatus stop(WorkflowRun run, Consumer<WorkflowRun> checkpoint, String reason) {
        return engine().stop(run, workspace, log, checkpoint, reason);
    }

    /** Serialises the run exactly as the run store persists it. */
    static String snapshot(WorkflowRun run) {
        return Json.MAPPER.writeValueAsString(run);
    }

    static WorkflowRun restore(String snapshot) {
        return Json.MAPPER.readValue(snapshot, WorkflowRun.class);
    }

    /**
     * A checkpoint consumer that keeps every persisted snapshot together with the number of events logged when it
     * was taken, so a test can tell which snapshot a restart right after a given event would resume from.
     */
    final class Snapshots implements Consumer<WorkflowRun> {

        record Taken(int events, String json) {
            WorkflowRun restore() {
                return EngineHarness.restore(json);
            }
        }

        final List<Taken> taken = new CopyOnWriteArrayList<>();

        @Override
        public void accept(WorkflowRun run) {
            taken.add(new Taken(log.events().size(), snapshot(run)));
        }

        /** The latest snapshot a process that stopped right after event {@code seq} would resume from. */
        Taken latestAt(long seq) {
            return taken.stream().filter(t -> t.events() <= seq).reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("no snapshot at or before event " + seq));
        }

        /** The first snapshot taken after event {@code seq} and before event {@code beforeSeq}, if any. */
        Optional<Taken> between(long seq, long beforeSeq) {
            return taken.stream().filter(t -> t.events() >= seq && t.events() < beforeSeq).findFirst();
        }
    }
}
