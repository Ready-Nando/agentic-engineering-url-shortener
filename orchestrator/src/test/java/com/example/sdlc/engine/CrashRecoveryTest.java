package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.refs;
import static com.example.sdlc.engine.EngineHarness.restore;
import static com.example.sdlc.engine.EngineHarness.snapshot;
import static com.example.sdlc.engine.EngineHarness.task;
import static com.example.sdlc.engine.EngineHarness.verifyTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.ChangeSet;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.Workspace;

/**
 * Crash consistency of rollbacks: every rollback is checkpointed once run state reflects it, and a restart from a
 * snapshot taken before a rollback (the rollback happened, the checkpoint was lost) reconciles the applied-change
 * stack with the disk instead of refusing to compensate.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CrashRecoveryTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";
    private static final String HELPER = "src/main/java/demo/Helper.java";

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- rollbacks are checkpointed

    @Test
    void snapshotPersistedAfterAnExitGateRollbackNoLongerListsTheChangeSet() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature " + ctx.attempt(), FileChange.create(FEATURE,
                        ctx.attempt() == 1 ? "class Feature { broken }\n" : "class Feature {}\n")))
                .gate("compiles", ctx -> ctx.workspace().read(FEATURE).orElse("").contains("broken")
                        ? GateResult.fail("does not compile", List.of("Feature.java:1")) : GateResult.pass("compiles"));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2).withGates(List.of(), List.of("compiles")));
        EngineHarness.Snapshots snapshots = harness.new Snapshots();

        assertThat(harness.execute(run, snapshots)).isEqualTo(RunStatus.COMPLETED);

        ExecutionEvent rollback = harness.events(EventType.CHANGESET_ROLLED_BACK, "impl").getFirst();
        assertThat(rollback.text("cause")).isEqualTo("attempt-rejected");
        String rolledBack = rollback.text("changeSet");
        assertThat(snapshots.latestAt(rollback.seq() - 1).restore().appliedChanges())
                .as("durable right after apply").extracting(AppliedChangeSet::id).containsExactly(rolledBack);
        // Persisted before the attempt is even marked failed: a restart after the rollback sees a consistent stack.
        WorkflowRun persisted = snapshots.between(rollback.seq(), harness.firstSeq(EventType.ATTEMPT_FAILED, "impl"))
                .orElseThrow(() -> new AssertionError("no checkpoint between the rollback and the attempt failure")).restore();
        assertThat(persisted.appliedChanges()).isEmpty();
    }

    @Test
    void snapshotPersistedAfterAReworkRollbackNoLongerListsTheChangeSet() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE,
                        "class Feature {\n    int a = " + (ctx.feedback().isEmpty() ? 1 : 2) + ";\n}\n")))
                .gate("tests-pass", ctx -> ctx.workspace().read(FEATURE).orElse("").contains("int a = 2;")
                        ? GateResult.pass("passed") : GateResult.defect("FeatureTest failed", List.of("expected a == 2"), List.of()))
                .capability("verify", List.of("tests-pass"), null, ctx -> output("verification", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2), verifyTask("verify", "verify", List.of("impl"), "impl"));
        EngineHarness.Snapshots snapshots = harness.new Snapshots();

        assertThat(harness.execute(run, snapshots)).isEqualTo(RunStatus.COMPLETED);

        ExecutionEvent rollback = harness.events(EventType.CHANGESET_ROLLED_BACK, "impl").getFirst();
        assertThat(rollback.text("cause")).isEqualTo("rework");
        WorkflowRun persisted = snapshots.between(rollback.seq(), nextStart(harness, "impl", rollback.seq()))
                .orElseThrow(() -> new AssertionError("no checkpoint between the rework rollback and the re-run")).restore();
        assertThat(persisted.appliedChanges()).extracting(AppliedChangeSet::id).doesNotContain(rollback.text("changeSet"));
        assertThat(persisted.appliedChanges()).isEmpty();
        assertThat(persisted.task("impl").status()).isEqualTo(TaskStatus.PENDING);
        assertThat(persisted.artifacts().current(ArtifactKeys.changesOf("impl"))).isEmpty();
    }

    @Test
    void snapshotPersistedAfterAnInvalidationRollbackNoLongerListsTheChangeSet() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        EngineHarness harness = reviewedFeature(tempDir);
        harness.human = request -> Optional.of(asked.incrementAndGet() == 1
                ? HumanResponse.requestChanges("rita", "rename the feature", "impl") : HumanResponse.approve("rita", "ok"));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2), task("review", "review", "impl"));
        EngineHarness.Snapshots snapshots = harness.new Snapshots();

        assertThat(harness.execute(run, snapshots)).isEqualTo(RunStatus.COMPLETED);

        ExecutionEvent rollback = harness.events(EventType.CHANGESET_ROLLED_BACK, "impl").getFirst();
        assertThat(rollback.text("cause")).isEqualTo("invalidation");
        WorkflowRun persisted = snapshots.between(rollback.seq(), nextStart(harness, "impl", rollback.seq()))
                .orElseThrow(() -> new AssertionError("no checkpoint between the invalidation rollback and the re-run")).restore();
        assertThat(persisted.appliedChanges()).isEmpty();
        assertThat(persisted.task("impl").status()).isEqualTo(TaskStatus.PENDING);
        assertThat(persisted.artifacts().current(ArtifactKeys.changesOf("impl"))).isEmpty();
        assertThat(persisted.humanRequests()).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(HumanRequest.Status.APPLIED));
    }

    // ---------------------------------------------------------------- restart from a stale snapshot

    @Test
    void resumeFromASnapshotThatPredatesALostRollbackReconcilesAndRerunsTheOwner() throws Exception {
        EngineHarness harness = reviewedFeature(tempDir);
        WorkflowRun first = harness.newRun(changeTask("impl", "implement", 2), task("review", "review", "impl"));
        EngineHarness.Snapshots snapshots = harness.new Snapshots();
        assertThat(harness.execute(first, snapshots)).isEqualTo(RunStatus.AWAITING_HUMAN);
        String stale = snapshots.taken.getLast().json();
        AppliedChangeSet lost = first.appliedChanges().getFirst();
        // The process went on to roll the change set back (e.g. for rework) and died before its next checkpoint.
        harness.workspace.rollback(lost);
        assertThat(harness.workspace.exists(FEATURE)).isFalse();

        WorkflowRun resumed = restore(stale);
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).containsExactly(lost.id());
        assertThat(resumed.task("impl").status()).isEqualTo(TaskStatus.SUCCEEDED);
        harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        EngineHarness.Snapshots afterRestart = harness.new Snapshots();

        assertThat(harness.execute(resumed, afterRestart)).isEqualTo(RunStatus.COMPLETED);

        assertThat(resumed.statusReason()).doesNotContain("manual intervention");
        assertThat(harness.events(EventType.CHANGESET_RECOVERED, "impl")).singleElement().satisfies(e -> {
            assertThat(e.text("changeSet")).isEqualTo(lost.id());
            assertThat(e.data()).containsEntry("diskTouched", false);
        });
        assertThat(harness.events(EventType.CHANGESET_ROLLED_BACK, "impl")).as("no second rollback is counted").isEmpty();
        // The owner's committed change was gone, so it was hard-invalidated and ran again (its consumer too).
        assertThat(harness.executions("impl")).isEqualTo(2);
        assertThat(harness.executions("review")).isEqualTo(2);
        assertThat(resumed.appliedChanges()).singleElement().satisfies(applied -> {
            assertThat(applied.id()).isNotEqualTo(lost.id());
            assertThat(resumed.artifacts().current(ArtifactKeys.changesOf("impl")).orElseThrow().content().path("changeSet").asString())
                    .isEqualTo(applied.id());
        });
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature {}\n");
        assertStackMatchesDisk(resumed, harness.workspace);
        // The reconciled stack is durable before anything runs again.
        assertThat(afterRestart.taken.getFirst().restore().appliedChanges()).isEmpty();
    }

    @Test
    void resumeOfAnOrphanedAttemptWhoseRollbackWasLostRerunsItWithoutManualIntervention() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n")));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1));
        run.status(RunStatus.RUNNING, null);
        run.task("impl").startAttempt();
        AppliedChangeSet orphan = harness.workspace.apply(run.nextId("cs"), "impl", 1, new ChangeSet("feature", "LOW",
                List.of(FileChange.create(FEATURE, "class Feature { rejected }\n")), Map.of()));
        run.pushApplied(orphan);
        String stale = snapshot(run);
        // Its exit gate failed and the rollback happened, but the process died before it was checkpointed.
        harness.workspace.rollback(orphan);

        WorkflowRun resumed = restore(stale);
        assertThat(harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.events(EventType.CHANGESET_RECOVERED, "impl")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("diskTouched", false));
        assertThat(harness.events(EventType.CHANGESET_ROLLED_BACK, "impl")).isEmpty();
        assertThat(resumed.task("impl").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.DISCARDED, AttemptRecord.Outcome.SUCCEEDED);
        assertThat(resumed.task("impl").executions()).isEqualTo(1);
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature {}\n");
        assertStackMatchesDisk(resumed, harness.workspace);
    }

    @Test
    void interruptedRollbackIsCompletedByRecovery() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n"),
                        FileChange.create(HELPER, "class Helper {}\n")));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1));
        AppliedChangeSet orphan = applyOrphan(harness, run, FileChange.create(FEATURE, "class Feature { v1 }\n"),
                FileChange.create(HELPER, "class Helper { v1 }\n"));
        String stale = snapshot(run);
        // Rollback restores in reverse order: HELPER was already back at its pre-image (absent), FEATURE was not.
        Files.delete(harness.workspace.root().resolve(HELPER));

        WorkflowRun resumed = restore(stale);
        assertThat(harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.events(EventType.CHANGESET_RECOVERED, "impl")).singleElement().satisfies(e -> {
            assertThat(e.text("changeSet")).isEqualTo(orphan.id());
            assertThat(e.data()).containsEntry("diskTouched", true).containsEntry("restored", List.of(FEATURE));
            assertThat(e.text("workspaceHash")).as("back at the baseline before the task re-ran").isEqualTo(harness.workspace.baselineHash());
        });
        assertThat(harness.events(EventType.CHANGESET_ROLLED_BACK, "impl")).isEmpty();
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature {}\n");
        assertThat(harness.workspace.read(HELPER)).hasValue("class Helper {}\n");
        assertThat(resumed.appliedChanges()).singleElement().satisfies(applied -> assertThat(applied.id()).isNotEqualTo(orphan.id()));
        assertStackMatchesDisk(resumed, harness.workspace);
    }

    @Test
    void fileMatchingNeitherItsPreNorItsPostImageStillFailsClosed() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n")));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1));
        AppliedChangeSet orphan = applyOrphan(harness, run, FileChange.create(FEATURE, "class Feature { v1 }\n"));
        String stale = snapshot(run);
        String edited = "class Feature { edited by hand }\n";
        Files.writeString(harness.workspace.root().resolve(FEATURE), edited);

        WorkflowRun resumed = restore(stale);
        assertThat(harness.execute(resumed)).isEqualTo(RunStatus.HALTED);

        assertThat(harness.count(EventType.CHANGESET_RECOVERED)).isZero();
        assertThat(resumed.statusReason()).contains("manual intervention");
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).containsExactly(orphan.id());
        assertThat(harness.workspace.read(FEATURE)).hasValue(edited);
        assertThat(harness.executions("impl")).isZero();
    }

    @Test
    void cancelFromAStaleSnapshotCompensatesEverythingAndRestoresTheBaseline() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change(ctx.task().id(), FileChange.create(
                        "src/main/java/demo/" + ctx.task().id() + ".java", "class " + ctx.task().id() + " {}\n")))
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("review", List.of("signoff"), null, ctx -> output("review", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))));
        WorkflowRun first = harness.newRun(changeTask("implA", "implement", 1), changeTask("implB", "implement", 1, "implA"),
                task("review", "review", "implB"));
        EngineHarness.Snapshots snapshots = harness.new Snapshots();
        assertThat(harness.execute(first, snapshots)).isEqualTo(RunStatus.AWAITING_HUMAN);
        String stale = snapshots.taken.getLast().json();
        assertThat(first.appliedChanges()).extracting(AppliedChangeSet::taskId).containsExactly("implA", "implB");
        AppliedChangeSet lost = first.appliedChanges().getLast();
        harness.workspace.rollback(lost);

        WorkflowRun resumed = restore(stale);
        EngineHarness.Snapshots afterCancel = harness.new Snapshots();
        assertThat(harness.stop(resumed, afterCancel, "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).contains("all changes compensated").doesNotContain("manual intervention");
        assertThat(harness.workspace.contentHash()).isEqualTo(harness.workspace.baselineHash());
        assertThat(resumed.appliedChanges()).isEmpty();
        assertThat(harness.events(EventType.CHANGESET_RECOVERED, "implB")).singleElement()
                .satisfies(e -> assertThat(e.text("changeSet")).isEqualTo(lost.id()));
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.CHANGESET_ROLLED_BACK)
                .extracting(ExecutionEvent::taskId, e -> e.text("cause")).containsExactly(tuple("implA", "safe-stop"));
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.WORKSPACE_RESTORED).last()
                .satisfies(e -> assertThat(e.data()).containsEntry("verified", true));
        assertThat(afterCancel.taken.getLast().restore().appliedChanges()).isEmpty();
        assertThat(resumed.artifacts().current(ArtifactKeys.changesOf("implA"))).isEmpty();
        assertThat(resumed.artifacts().current(ArtifactKeys.changesOf("implB"))).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** A change task whose result a reviewer signs off; the review reads the change set, so it is its consumer. */
    private static EngineHarness reviewedFeature(Path tempDir) throws Exception {
        return new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n")))
                .gate("signoff", ctx -> GateResult.approval("sign off " + ctx.task().id(), List.of()))
                .capability("review", List.of("signoff"), null, ctx -> output("review", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))));
    }

    /** State a crash right after apply leaves behind: the attempt started, its change set written and durable. */
    private static AppliedChangeSet applyOrphan(EngineHarness harness, WorkflowRun run, FileChange... changes) {
        run.status(RunStatus.RUNNING, null);
        run.task("impl").startAttempt();
        AppliedChangeSet orphan = harness.workspace.apply(run.nextId("cs"), "impl", 1,
                new ChangeSet("orphan", "LOW", List.of(changes), Map.of()));
        run.pushApplied(orphan);
        return orphan;
    }

    private static long nextStart(EngineHarness harness, String taskId, long after) {
        return harness.events(EventType.TASK_STARTED, taskId).stream().mapToLong(ExecutionEvent::seq).filter(seq -> seq > after)
                .min().orElseThrow(() -> new AssertionError(taskId + " never started again"));
    }

    /** Every file of every applied change set holds exactly what that change set (or a later one) wrote. */
    private static void assertStackMatchesDisk(WorkflowRun run, Workspace workspace) {
        Map<String, String> expected = new HashMap<>();
        run.appliedChanges().forEach(applied -> applied.files().forEach(f -> expected.put(f.path(), f.postHash())));
        expected.forEach((path, hash) -> assertThat(workspace.hash(path).orElse(null)).as(path).isEqualTo(hash));
    }
}
