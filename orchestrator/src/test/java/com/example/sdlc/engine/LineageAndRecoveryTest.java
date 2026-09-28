package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.task;
import static com.example.sdlc.engine.EngineHarness.verifyTask;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.ChangeSet;
import com.example.sdlc.workspace.FileChange;

/** Lineage recorded by the engine (file reads, visibility) and what it is used for: invalidation and recovery. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LineageAndRecoveryTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";

    @TempDir
    Path tempDir;

    @Test
    void reworkHardInvalidatesALaterEditOfTheSameFileAndRollsItBackFirst() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implA", ctx -> change("implA " + ctx.attempt(), FileChange.create(FEATURE,
                        "class Feature {\n    int a = " + (ctx.feedback().isEmpty() ? 1 : 2) + ";\n}\n")))
                .changeCapability("implB", ctx -> {
                    // Reading the file is what records implA's change set as an input of this task.
                    ctx.readFile(FEATURE).orElseThrow();
                    return change("implB", FileChange.edit(FEATURE, "class Feature {", "class Feature {\n    int b = 1;"));
                })
                .gate("tests-pass", ctx -> ctx.workspace().read(FEATURE).orElse("").contains("int a = 2;")
                        ? GateResult.pass("passed") : GateResult.defect("FeatureTest failed", List.of("expected a == 2"), List.of()))
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("verification", "ran");
                });
        // verify only names implA; implB is affected purely through lineage (it read and edited implA's file).
        WorkflowRun run = harness.newRun(changeTask("implA", "implA", 2), changeTask("implB", "implB", 1, "implA"),
                verifyTask("verify", "verify", List.of("implA"), "implB"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.events(EventType.TASK_SUCCEEDED, "implB").getFirst().data().get("inputs"))
                .isEqualTo(List.of("changes/implA@v1"));
        List<ExecutionEvent> rollbacks = harness.log.events().stream().filter(e -> e.type() == EventType.CHANGESET_ROLLED_BACK).toList();
        assertThat(rollbacks).extracting(ExecutionEvent::taskId).containsExactly("implB", "implA");
        assertThat(rollbacks).extracting(e -> e.text("cause")).containsOnly("rework");
        assertThat(rollbacks.getFirst().seq()).isGreaterThan(harness.firstSeq(EventType.REWORK_REQUESTED, "verify"));
        assertThat(harness.events(EventType.TASK_INVALIDATED, "implB")).singleElement()
                .satisfies(e -> assertThat(e.message()).startsWith("consumed output of invalidated work"));
        assertThat(harness.executions("implA")).isEqualTo(2);
        assertThat(harness.executions("implB")).isEqualTo(2);
        assertThat(harness.executions("verify")).isEqualTo(2);
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature {\n    int b = 1;\n    int a = 2;\n}\n");
        assertThat(run.task("implB").inputs()).extracting(Object::toString).containsExactly("changes/implA@v2");
        assertThat(run.appliedChanges()).extracting(AppliedChangeSet::taskId).containsExactly("implA", "implB");
    }

    @Test
    void artifactsOfNonAncestorsAreInvisibleAndNeverRecordedAsInputs() throws Exception {
        AtomicBoolean found = new AtomicBoolean(true);
        AtomicReference<FailureKind> readFailure = new AtomicReference<>();
        List<String> listed = new CopyOnWriteArrayList<>();
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("produce", ctx -> output("secret", "classified"))
                .capability("outsider", ctx -> {
                    found.set(ctx.find("secret", Map.class).isPresent());
                    try {
                        ctx.read("secret", Map.class);
                    } catch (TaskFailure failure) {
                        readFailure.set(failure.kind());
                    }
                    ctx.readAll("").forEach(a -> listed.add(a.key()));
                    return output("outsider", "done");
                })
                .capability("descendant", ctx -> output("descendant", ctx.read("secret", Map.class).get("value")));
        // One worker: produce is committed (and "secret" current) before outsider is dispatched.
        harness.settings = new WorkflowEngine.Settings(1, 6, 2);
        WorkflowRun run = harness.newRun(task("produce", "produce"), task("outsider", "outsider"),
                task("descendant", "descendant", "produce"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.firstSeq(EventType.TASK_STARTED, "outsider")).isGreaterThan(harness.firstSeq(EventType.TASK_SUCCEEDED, "produce"));
        assertThat(found).isFalse();
        assertThat(readFailure).hasValue(FailureKind.FATAL);
        assertThat(listed).doesNotContain("secret");
        assertThat(run.task("outsider").inputs()).isEmpty();
        assertThat(run.artifacts().current("outsider").orElseThrow().inputs()).isEmpty();
        // Positive control: a descendant sees it, and the read is recorded.
        assertThat(run.task("descendant").inputs()).extracting(Object::toString).containsExactly("secret@v1");
        assertThat(run.artifacts().current("descendant").orElseThrow().content().path("value").asString()).isEqualTo("classified");
    }

    @Test
    void orphanedAttemptThatAppliedAChangeSetIsCompensatedAndReplaysAsTheSameInvocation() throws Exception {
        List<Integer> invocations = new CopyOnWriteArrayList<>();
        List<Integer> attempts = new CopyOnWriteArrayList<>();
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> {
                    invocations.add(ctx.invocation());
                    attempts.add(ctx.attempt());
                    return change("feature", FileChange.create(FEATURE, "class Feature {}\n"));
                })
                .capability("after", ctx -> output("after", ctx.readFile(FEATURE).orElse("missing")));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1), task("after", "after", "impl"));
        // Crash after apply: the attempt started, its change set was written and made durable (the engine
        // checkpoints right after apply), then the process died before the attempt committed.
        run.status(RunStatus.RUNNING, null);
        run.task("impl").startAttempt();
        AppliedChangeSet orphan = harness.workspace.apply(run.nextId("cs"), "impl", 1, new ChangeSet("feature", "LOW",
                List.of(FileChange.create(FEATURE, "class Feature {} // written by the orphaned attempt\n")), Map.of()));
        run.pushApplied(orphan);
        assertThat(run.artifacts().current(ArtifactKeys.changesOf("impl"))).isEmpty();

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.CHANGESET_ROLLED_BACK).singleElement().satisfies(e -> {
            assertThat(e.taskId()).isEqualTo("impl");
            assertThat(e.text("changeSet")).isEqualTo(orphan.id());
            assertThat(e.text("cause")).isEqualTo("orphaned");
        });
        assertThat(harness.eventTypes("impl")).startsWith(EventType.CHANGESET_ROLLED_BACK, EventType.ATTEMPT_DISCARDED);
        assertThat(invocations).as("the orphan does not count as an execution").containsExactly(1);
        assertThat(attempts).containsExactly(1);
        assertThat(run.task("impl").executions()).isEqualTo(1);
        assertThat(run.task("impl").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.DISCARDED, AttemptRecord.Outcome.SUCCEEDED);
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature {}\n");
        assertThat(run.appliedChanges()).singleElement().satisfies(applied -> {
            assertThat(applied.id()).isNotEqualTo(orphan.id());
            assertThat(run.artifacts().current(ArtifactKeys.changesOf("impl")).orElseThrow().content().path("changeSet").asString())
                    .isEqualTo(applied.id());
        });
        assertThat(run.artifacts().current("after").map(Artifact::content).orElseThrow().path("value").asString())
                .isEqualTo("class Feature {}\n");
    }
}
