package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.task;
import static com.example.sdlc.engine.EngineHarness.verifyTask;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.FileChange;

/** Entry gates and the coordinator's fault barrier: failures end in a safe stop, never in an escaping exception. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FaultBarrierTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";

    @TempDir
    Path tempDir;

    @Test
    void entryGateThatThrowsFailsItsTaskAndHaltsSafelyWithChangesCompensated() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n")))
                .gate("explodes", ctx -> {
                    throw new IllegalStateException("gate bug");
                })
                .capability("check", List.of("explodes"), List.of(), null, ctx -> output("checked", true))
                .capability("after", ctx -> output("after", true));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1), task("check", "check", "impl"),
                task("after", "after", "check"));
        String baseline = harness.workspace.baselineHash();

        RunStatus status = harness.execute(run);

        assertThat(status).isEqualTo(RunStatus.HALTED);
        assertThat(harness.events(EventType.GATE_FAILED, "check")).singleElement().satisfies(e -> {
            assertThat(e.text("phase")).isEqualTo("entry");
            assertThat(e.text("gate")).isEqualTo("explodes");
            assertThat(e.message()).contains("gate error: IllegalStateException: gate bug");
        });
        assertThat(run.task("check").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(harness.executions("check")).isZero();
        assertThat(run.task("after").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(run.task("impl").status()).isEqualTo(TaskStatus.ROLLED_BACK);
        assertThat(run.appliedChanges()).isEmpty();
        assertThat(harness.workspace.contentHash()).isEqualTo(baseline);
        assertThat(run.statusReason()).contains("entry gate explodes failed").contains("workspace restored to baseline");
    }

    @Test
    void rollbackRefusedDuringReworkHaltsTheRunWithAClearReasonInsteadOfEscaping() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature { int v = 41; }\n")))
                .gate("tests-pass", ctx -> GateResult.defect("FeatureTest failed", List.of("expected 42"), List.of()))
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    // Out-of-band edit of a governed file: the rework's rollback must refuse to overwrite it.
                    Files.writeString(ctx.workspace().root().resolve(FEATURE), "class Feature { int v = 99; } // hand edit\n");
                    return output("verification", "ran");
                });
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2), verifyTask("verify", "verify", List.of("impl"), "impl"));

        RunStatus status = harness.execute(run);

        assertThat(status).isEqualTo(RunStatus.HALTED);
        assertThat(run.statusReason())
                .startsWith("coordinator error: WorkspaceException: cannot roll back")
                .contains(FEATURE + " was modified after it was applied")
                .contains("COMPENSATION FAILED")
                .contains("manual intervention required");
        assertThat(run.verdict()).isEqualTo("NOT_READY");
        assertThat(harness.count(EventType.REWORK_REQUESTED)).isEqualTo(1);
        assertThat(harness.count(EventType.CHANGESET_ROLLED_BACK)).isZero();
        assertThat(harness.log.events().getLast().type()).isEqualTo(EventType.RUN_HALTED);
        // The hand edit is preserved, never silently destroyed.
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature { int v = 99; } // hand edit\n");
        assertThat(harness.executions("impl")).isEqualTo(1);
    }

    @Test
    void failingEntryGateKeepsTheHandlerFromRunningAndBlocksDependents() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("first", ctx -> output("first", 1))
                .gate("closed", ctx -> GateResult.fail("preconditions not met", List.of("baseline is red")))
                .capability("guarded", List.of("closed"), List.of(), null, ctx -> output("guarded", 1))
                .capability("after", ctx -> output("after", 1));
        WorkflowRun run = harness.newRun(task("first", "first"), task("guarded", "guarded", "first"),
                task("after", "after", "guarded"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertEntryGateEnforced(harness, run, "closed");
    }

    @Test
    void entryGateDeclaredByThePlanIsEnforcedToo() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("first", ctx -> output("first", 1))
                .gate("closed", ctx -> GateResult.fail("preconditions not met", List.of("baseline is red")))
                .capability("plain", ctx -> output("plain/" + ctx.task().id(), 1));
        TaskSpec guarded = new TaskSpec("guarded", "guarded", Stage.IMPLEMENTATION, "plain", List.of("first"), "", List.of(),
                List.of(), List.of(), List.of("closed"), List.of(), 1, null);
        WorkflowRun run = harness.newRun(task("first", "first"), guarded, task("after", "plain", "guarded"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertEntryGateEnforced(harness, run, "closed");
    }

    private static void assertEntryGateEnforced(EngineHarness harness, WorkflowRun run, String gate) {
        assertThat(harness.executions("guarded")).isZero();
        assertThat(harness.events(EventType.TASK_STARTED, "guarded")).isEmpty();
        assertThat(harness.events(EventType.GATE_FAILED, "guarded")).singleElement().satisfies(e -> {
            assertThat(e.text("phase")).isEqualTo("entry");
            assertThat(e.text("gate")).isEqualTo(gate);
        });
        assertThat(run.task("guarded").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(run.task("after").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(harness.executions("after")).isZero();
        assertThat(run.task("first").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(run.statusReason()).contains("entry gate " + gate + " failed");
    }
}
