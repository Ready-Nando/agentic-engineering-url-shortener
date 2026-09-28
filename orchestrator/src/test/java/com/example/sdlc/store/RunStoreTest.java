package com.example.sdlc.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.engine.Capability;
import com.example.sdlc.engine.EventLog;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.engine.TaskStatus;
import com.example.sdlc.engine.WorkflowEngine;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanGateway;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.workspace.ChangeSet;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.Workspace;

/**
 * A paused run must be resumable by a different process: everything the engine needs is in run.json and
 * events.jsonl. The second engine here shares nothing in memory with the first.
 */
class RunStoreTest {

    @TempDir
    Path temp;

    @Test
    void pausedRunSurvivesARoundTripThroughDiskAndResumesWhereItStopped() throws Exception {
        Path module = temp.resolve("module");
        Files.createDirectories(module.resolve("src/main/resources/db/migration"));
        Files.writeString(module.resolve("src/main/resources/db/migration/V1__init.sql"), "CREATE TABLE t (id INT);\n");
        Workspace workspace = Workspace.create(module, Map.of(), temp.resolve("runs/run-1"));
        RunStore store = new RunStore(temp.resolve("runs"));

        TaskSpec migrate = new TaskSpec("migrate", "migrate", Stage.IMPLEMENTATION, "migrate", List.of(), "", List.of(),
                List.of("src/main/resources/db/migration/*.sql"), List.of(), List.of(), List.of(), 1, null);
        TaskSpec report = TaskSpec.of("report", Stage.DOCUMENTATION, "report", List.of("migrate"));
        WorkflowRun run = new WorkflowRun("run-1", "test", "persisted run", "req", Instant.now(),
                WorkflowPlan.of(1, List.of(migrate, report), "", ""), Map.of());

        assertThat(engine(HumanGateway.DEFERRED).execute(run, workspace, log(store, run, List.of()), store::save))
                .isEqualTo(RunStatus.AWAITING_HUMAN);

        WorkflowRun reloaded = store.load("run-1");
        assertThat(reloaded.status()).isEqualTo(RunStatus.AWAITING_HUMAN);
        assertThat(reloaded.task("migrate").status()).isEqualTo(TaskStatus.AWAITING_HUMAN);
        HumanRequest pending = reloaded.pendingHumanRequests().getFirst();
        assertThat(pending.policyRules()).contains("CC-02");
        assertThat(reloaded.task("migrate").parked().result().changes().changes()).hasSize(1);

        reloaded.answer(pending.id(), HumanResponse.approve("carol", "ok"), Instant.now());
        store.save(reloaded);
        WorkflowRun resumed = store.load("run-1");
        List<ExecutionEvent> previous = store.events("run-1");
        Workspace reopened = Workspace.open(temp.resolve("runs/run-1/workspace"), temp.resolve("runs/run-1/baseline"));

        assertThat(engine(HumanGateway.DEFERRED).execute(resumed, reopened, log(store, resumed, previous), store::save))
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(reopened.exists("src/main/resources/db/migration/V2__add.sql")).isTrue();
        List<ExecutionEvent> events = store.events("run-1");
        assertThat(events).extracting(ExecutionEvent::type).contains(EventType.RUN_PAUSED, EventType.RUN_RESUMED, EventType.RUN_COMPLETED);
        assertThat(events).extracting(ExecutionEvent::seq).doesNotHaveDuplicates().isSorted();
        assertThat(store.load("run-1").artifacts().current("approval/" + pending.id())).isPresent();
    }

    private static EventLog log(RunStore store, WorkflowRun run, List<ExecutionEvent> previous) {
        EventLog log = new EventLog(run.id(), Clock.systemUTC(), previous);
        log.subscribe(store::append);
        return log;
    }

    private static WorkflowEngine engine(HumanGateway human) {
        Capability migrate = new Capability("migrate", Stage.IMPLEMENTATION, Capability.Role.WORK, true, true, List.of("src/"), false, 1,
                List.of(), List.of(), null,
                context -> TaskResult.withChanges("add column", new ChangeSet("add column", "LOW",
                        List.of(FileChange.create("src/main/resources/db/migration/V2__add.sql", "ALTER TABLE t ADD COLUMN c INT;\n")), Map.of()),
                        Map.of()));
        Capability report = new Capability("report", Stage.DOCUMENTATION, Capability.Role.WORK, true, false, List.of(), false, 1,
                List.of(), List.of(), null,
                context -> TaskResult.of("done", "report", OutputArtifact.of("report", Map.of("changes", context.readAll("changes/").size()))));
        return new WorkflowEngine(List.of(migrate, report), List.of(), new ChangePolicy(), human, Clock.systemUTC(),
                WorkflowEngine.Settings.defaults());
    }
}
