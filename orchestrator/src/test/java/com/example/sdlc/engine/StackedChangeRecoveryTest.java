package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.refs;
import static com.example.sdlc.engine.EngineHarness.restore;
import static com.example.sdlc.engine.EngineHarness.task;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.Workspace;

/**
 * Restart from a snapshot that predates lost rollbacks of change sets stacked on the same file: the later change
 * set's file then holds an earlier change set's pre-image, which neither of its own images explains. Rollbacks
 * happen newest first, one whole change set at a time, so the stack is reconciled as a rolled-back suffix.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StackedChangeRecoveryTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";
    private static final String HELPER = "src/main/java/demo/Helper.java";
    private static final String OTHER = "src/main/java/demo/Other.java";
    private static final String OTHER_TWO = "src/main/java/demo/OtherTwo.java";
    private static final String NEXT = "src/main/java/demo/Next.java";

    @TempDir
    Path tempDir;

    @Test
    void bothStackedChangeSetsRolledBackBeforeTheLostSaveAreRecoveredAndRedoneOnResume() throws Exception {
        Stacked stacked = paused("implA", "implB");
        stacked.lose(1);
        stacked.lose(0);
        assertThat(stacked.harness.workspace.exists(FEATURE)).isFalse();

        WorkflowRun resumed = restore(stacked.stale);
        stacked.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        EngineHarness.Snapshots afterRestart = stacked.harness.new Snapshots();
        assertThat(stacked.harness.execute(resumed, afterRestart)).isEqualTo(RunStatus.COMPLETED);

        assertThat(resumed.statusReason()).doesNotContain("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1), stacked.id(0));
        assertThat(stacked.harness.log.events()).filteredOn(e -> e.type() == EventType.CHANGESET_RECOVERED)
                .allSatisfy(e -> assertThat(e.data()).containsEntry("diskTouched", false));
        assertThat(afterRestart.taken.getFirst().restore().appliedChanges()).as("reconciled stack durable first").isEmpty();
        assertThat(stacked.harness.executions("implA")).isEqualTo(2);
        assertThat(stacked.harness.executions("implB")).isEqualTo(2);
        assertThat(stacked.harness.workspace.read(FEATURE)).hasValue("class Feature { b }\n");
        assertStackMatchesDisk(resumed, stacked.harness.workspace);
    }

    @Test
    void cancelAfterBothStackedChangeSetsWereRolledBackRestoresTheBaseline() throws Exception {
        Stacked stacked = paused("implA", "implB");
        stacked.lose(1);
        stacked.lose(0);

        WorkflowRun resumed = restore(stacked.stale);
        EngineHarness.Snapshots afterCancel = stacked.harness.new Snapshots();
        assertThat(stacked.harness.stop(resumed, afterCancel, "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).doesNotContain("COMPENSATION FAILED").doesNotContain("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1), stacked.id(0));
        assertThat(stacked.harness.count(EventType.CHANGESET_ROLLED_BACK)).isZero();
        assertThat(stacked.harness.workspace.contentHash()).isEqualTo(stacked.harness.workspace.baselineHash());
        assertThat(stacked.harness.log.events()).filteredOn(e -> e.type() == EventType.WORKSPACE_RESTORED).last()
                .satisfies(e -> assertThat(e.data()).containsEntry("verified", true));
        assertThat(resumed.appliedChanges()).isEmpty();
        assertThat(afterCancel.taken.getLast().restore().appliedChanges()).isEmpty();
    }

    @Test
    void interruptedRollbackOfTheOlderStackedChangeSetIsCompleted() throws Exception {
        Stacked stacked = paused("implA", "implB");
        stacked.lose(1);
        // implA's rollback restores its files in reverse order: HELPER was back (absent), FEATURE was not yet.
        Files.delete(stacked.harness.workspace.root().resolve(HELPER));
        assertThat(stacked.harness.workspace.read(FEATURE)).hasValue("class Feature { a }\n");

        WorkflowRun resumed = restore(stacked.stale);
        assertThat(stacked.harness.stop(resumed, stacked.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).doesNotContain("manual intervention");
        assertThat(stacked.harness.events(EventType.CHANGESET_RECOVERED, "implB")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("diskTouched", false).containsEntry("restored", List.of()));
        assertThat(stacked.harness.events(EventType.CHANGESET_RECOVERED, "implA")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("diskTouched", true).containsEntry("restored", List.of(FEATURE)));
        assertThat(stacked.harness.workspace.contentHash()).isEqualTo(stacked.harness.workspace.baselineHash());
        assertThat(resumed.appliedChanges()).isEmpty();
    }

    @Test
    void threeStackedChangeSetsRolledBackAreAllRecovered() throws Exception {
        Stacked stacked = paused("implA", "implB", "implC");
        stacked.lose(2);
        stacked.lose(1);
        stacked.lose(0);

        WorkflowRun resumed = restore(stacked.stale);
        stacked.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        assertThat(stacked.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(2), stacked.id(1), stacked.id(0));
        assertThat(stacked.harness.executions("implC")).isEqualTo(2);
        assertThat(stacked.harness.workspace.read(FEATURE)).hasValue("class Feature { c }\n");
        assertStackMatchesDisk(resumed, stacked.harness.workspace);
    }

    @Test
    void onlyTheNewerStackedChangeSetRolledBackRecoversOnlyThatOne() throws Exception {
        Stacked stacked = paused("implA", "implB");
        stacked.lose(1);

        WorkflowRun resumed = restore(stacked.stale);
        stacked.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        assertThat(stacked.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1));
        assertThat(stacked.harness.executions("implA")).isEqualTo(1);
        assertThat(stacked.harness.executions("implB")).isEqualTo(2);
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).startsWith(stacked.id(0));
        assertStackMatchesDisk(resumed, stacked.harness.workspace);
    }

    @Test
    void stackStillOnDiskIsNotReconciled() throws Exception {
        Stacked stacked = paused("implA", "implB");

        WorkflowRun resumed = restore(stacked.stale);
        resumed.answer(resumed.pendingHumanRequests().getFirst().id(), HumanResponse.approve("rita", "ok"), Instant.now());
        assertThat(stacked.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(stacked.harness.count(EventType.CHANGESET_RECOVERED)).isZero();
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).containsExactly(stacked.id(0), stacked.id(1));
        assertThat(stacked.harness.executions("implA")).isEqualTo(1);
        assertThat(stacked.harness.executions("implB")).isEqualTo(1);
    }

    @Test
    void rolledBackChangeSetBelowOneThatStayedAppliedIsRecovered() throws Exception {
        // Invalidation rolls back only the invalidated tasks' change sets (newest first), so with disjoint files a
        // change set can be rolled back while a later one stays applied.
        Stacked stacked = paused("implA", "implO", "implB");
        stacked.lose(2);
        stacked.lose(0);

        WorkflowRun resumed = restore(stacked.stale);
        assertThat(stacked.harness.stop(resumed, stacked.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).contains("all changes compensated").doesNotContain("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(2), stacked.id(0));
        assertThat(stacked.harness.log.events()).filteredOn(e -> e.type() == EventType.CHANGESET_ROLLED_BACK)
                .extracting(ExecutionEvent::taskId).containsExactly("implO");
        assertThat(stacked.harness.workspace.contentHash()).isEqualTo(stacked.harness.workspace.baselineHash());
    }

    @Test
    void middleChangeSetRolledBackAloneIsRecovered() throws Exception {
        Stacked stacked = paused("implA", "implO", "implB");
        stacked.lose(1);

        WorkflowRun resumed = restore(stacked.stale);
        assertThat(stacked.harness.stop(resumed, stacked.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).contains("all changes compensated").doesNotContain("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1));
        assertThat(stacked.harness.log.events()).filteredOn(e -> e.type() == EventType.CHANGESET_ROLLED_BACK)
                .extracting(ExecutionEvent::taskId).containsExactly("implB", "implA");
        assertThat(stacked.harness.workspace.contentHash()).isEqualTo(stacked.harness.workspace.baselineHash());
    }

    @Test
    void handEditOfAStackedFileStillFailsClosedAndIsPreserved() throws Exception {
        Stacked stacked = paused("implA", "implB");
        stacked.lose(1);
        stacked.lose(0);
        String edited = "class Feature { edited by hand }\n";
        Files.writeString(stacked.harness.workspace.root().resolve(FEATURE), edited);

        WorkflowRun resumed = restore(stacked.stale);
        assertThat(stacked.harness.stop(resumed, stacked.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).contains("COMPENSATION FAILED").contains("manual intervention");
        assertThat(stacked.harness.count(EventType.CHANGESET_RECOVERED)).isZero();
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).containsExactly(stacked.id(0), stacked.id(1));
        assertThat(stacked.harness.workspace.read(FEATURE)).hasValue(edited);
    }

    @Test
    void handEditBelowARolledBackSuffixStillFailsClosedAndIsPreserved() throws Exception {
        Stacked stacked = paused("implA", "implB");
        stacked.lose(1);
        String edited = "class Helper { edited by hand }\n";
        Files.writeString(stacked.harness.workspace.root().resolve(HELPER), edited);

        WorkflowRun resumed = restore(stacked.stale);
        assertThat(stacked.harness.stop(resumed, stacked.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).contains("COMPENSATION FAILED").contains("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1));
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).containsExactly(stacked.id(0));
        assertThat(stacked.harness.workspace.read(HELPER)).hasValue(edited);
        assertThat(stacked.harness.workspace.read(FEATURE)).hasValue("class Feature { a }\n");
    }

    @Test
    void olderChangeSetRolledBackInFullAndNewerUnrelatedOneInterruptedAreBothRecoveredOnResume() throws Exception {
        // A plan adoption rolls back removed tasks, then changed ones, under one checkpoint: the first batch rolled
        // implA back completely, the second was cut off in implP's rollback (OTHER_TWO back, OTHER not yet).
        Stacked stacked = paused("implA", "implP");
        stacked.lose(0);
        Files.delete(stacked.harness.workspace.root().resolve(OTHER_TWO));

        WorkflowRun resumed = restore(stacked.stale);
        stacked.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        assertThat(stacked.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(resumed.statusReason()).doesNotContain("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1), stacked.id(0));
        assertThat(stacked.harness.events(EventType.CHANGESET_RECOVERED, "implP")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("diskTouched", true).containsEntry("restored", List.of(OTHER)));
        assertThat(stacked.harness.events(EventType.CHANGESET_RECOVERED, "implA")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("diskTouched", false));
        assertThat(stacked.harness.executions("implA")).isEqualTo(2);
        assertThat(stacked.harness.executions("implP")).isEqualTo(2);
        assertThat(stacked.harness.workspace.read(OTHER_TWO)).hasValue("class OtherTwo {}\n");
        assertStackMatchesDisk(resumed, stacked.harness.workspace);
    }

    @Test
    void cancelAfterAnOlderFullRollbackAndANewerInterruptedOneRestoresTheBaseline() throws Exception {
        Stacked stacked = paused("implA", "implP");
        stacked.lose(0);
        Files.delete(stacked.harness.workspace.root().resolve(OTHER_TWO));

        WorkflowRun resumed = restore(stacked.stale);
        assertThat(stacked.harness.stop(resumed, stacked.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).doesNotContain("COMPENSATION FAILED").doesNotContain("manual intervention");
        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(1), stacked.id(0));
        assertThat(stacked.harness.count(EventType.CHANGESET_ROLLED_BACK)).isZero();
        assertThat(resumed.appliedChanges()).isEmpty();
        assertThat(stacked.harness.workspace.contentHash()).isEqualTo(stacked.harness.workspace.baselineHash());
    }

    @Test
    void interruptedWriteOfTheNewestChangeSetIsNotReadAsAnInterruptedRollbackOfAnOlderOne() throws Exception {
        // implD deletes HELPER (implA's) and creates NEXT; the process died after the first write. The disk also
        // fits "implD rolled back, then implA's rollback cut off", but that reading drops more committed work.
        Stacked stacked = paused("implA", "implO", "implD");
        long written = stacked.harness.firstSeq(EventType.CHANGESET_APPLIED, "implD");
        String writeAhead = stacked.snapshots.latestAt(written - 1).json();
        Files.delete(stacked.harness.workspace.root().resolve(NEXT));

        WorkflowRun resumed = restore(writeAhead);
        stacked.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        assertThat(stacked.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(recovered(stacked.harness)).containsExactly(stacked.id(2));
        assertThat(stacked.harness.events(EventType.CHANGESET_RECOVERED, "implD")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("restored", List.of(HELPER)));
        assertThat(stacked.harness.executions("implA")).isEqualTo(1);
        assertThat(stacked.harness.executions("implO")).isEqualTo(1);
        assertThat(stacked.harness.executions("implD")).isEqualTo(2);
        assertThat(resumed.appliedChanges()).extracting(AppliedChangeSet::id).startsWith(stacked.id(0), stacked.id(1));
        assertThat(stacked.harness.workspace.exists(HELPER)).isFalse();
        assertThat(stacked.harness.workspace.read(FEATURE)).hasValue("class Feature { a }\n");
        assertStackMatchesDisk(resumed, stacked.harness.workspace);
    }

    // ---------------------------------------------------------------- helpers

    /** A run paused at the reviewer's sign-off, its last durable snapshot, and the change sets it had applied. */
    private record Stacked(EngineHarness harness, String stale, List<AppliedChangeSet> stack, EngineHarness.Snapshots snapshots) {

        String id(int index) {
            return stack.get(index).id();
        }

        /** The rollback of {@code stack[index]} happened, but the checkpoint recording it was lost. */
        void lose(int index) {
            harness.workspace.rollback(stack.get(index));
        }
    }

    /**
     * Runs the given change tasks one after another, then a reviewer that reads their changes and waits for sign-off.
     * implA creates FEATURE and HELPER, implB and implC edit FEATURE in turn, implD deletes HELPER and creates NEXT,
     * implO creates an unrelated file and implP two.
     */
    private Stacked paused(String... changeTasks) throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> switch (ctx.task().id()) {
                    case "implA" -> change("a", FileChange.create(FEATURE, "class Feature { a }\n"),
                            FileChange.create(HELPER, "class Helper {}\n"));
                    case "implB" -> change("b", FileChange.edit(FEATURE, "{ a }", "{ b }"));
                    case "implC" -> change("c", FileChange.edit(FEATURE, "{ b }", "{ c }"));
                    case "implD" -> change("d", FileChange.delete(HELPER), FileChange.create(NEXT, "class Next {}\n"));
                    case "implP" -> change("p", FileChange.create(OTHER, "class Other {}\n"),
                            FileChange.create(OTHER_TWO, "class OtherTwo {}\n"));
                    default -> change("other", FileChange.create(OTHER, "class Other {}\n"));
                })
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("review", List.of("signoff"), null, ctx -> output("review", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))));
        TaskSpec[] tasks = new TaskSpec[changeTasks.length + 1];
        for (int i = 0; i < changeTasks.length; i++) {
            tasks[i] = i == 0 ? changeTask(changeTasks[i], "implement", 2) : changeTask(changeTasks[i], "implement", 2, changeTasks[i - 1]);
        }
        tasks[changeTasks.length] = task("review", "review", changeTasks[changeTasks.length - 1]);
        WorkflowRun first = harness.newRun(tasks);
        EngineHarness.Snapshots snapshots = harness.new Snapshots();
        assertThat(harness.execute(first, snapshots)).isEqualTo(RunStatus.AWAITING_HUMAN);
        assertThat(first.appliedChanges()).extracting(AppliedChangeSet::taskId).containsExactly(changeTasks);
        return new Stacked(harness, snapshots.taken.getLast().json(), first.appliedChanges(), snapshots);
    }

    private static List<String> recovered(EngineHarness harness) {
        return harness.log.events().stream().filter(e -> e.type() == EventType.CHANGESET_RECOVERED).map(e -> e.text("changeSet")).toList();
    }

    /** Every file of every applied change set holds exactly what that change set (or a later one) wrote. */
    private static void assertStackMatchesDisk(WorkflowRun run, Workspace workspace) {
        Map<String, String> expected = new HashMap<>();
        run.appliedChanges().forEach(applied -> applied.files().forEach(f -> expected.put(f.path(), f.postHash())));
        expected.forEach((path, hash) -> assertThat(workspace.hash(path).orElse(null)).as(path).isEqualTo(hash));
    }
}
