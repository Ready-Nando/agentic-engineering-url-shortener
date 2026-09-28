package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.FileChange;

/**
 * A change set's compensation record is durable before its first file is written, so a process that dies while
 * writing (or before the post-apply checkpoint) leaves a stack that restart reconciliation can repair, and a write
 * that fails part-way is undone before the attempt fails.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WriteAheadApplyTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";
    private static final String HELPER = "src/main/java/demo/Helper.java";
    private static final String LOCKED = "src/main/java/demo/locked/Locked.java";

    @TempDir
    Path tempDir;

    @Test
    void resumeAfterACrashPartWayThroughWritingUndoesTheWriteAndRerunsTheAttempt() throws Exception {
        Crashed crashed = crashedWhileWriting();
        // Files are written in order: FEATURE made it to disk, HELPER did not.
        Files.delete(crashed.harness.workspace.root().resolve(HELPER));

        WorkflowRun resumed = EngineHarness.restore(crashed.stale);
        crashed.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        assertThat(crashed.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(crashed.harness.events(EventType.CHANGESET_RECOVERED, "impl")).singleElement().satisfies(e -> {
            assertThat(e.text("changeSet")).isEqualTo(crashed.pending.id());
            assertThat(e.data()).containsEntry("diskTouched", true).containsEntry("restored", List.of(FEATURE));
            assertThat(e.text("workspaceHash")).isEqualTo(crashed.harness.workspace.baselineHash());
        });
        assertThat(crashed.harness.count(EventType.CHANGESET_ROLLED_BACK)).isZero();
        assertThat(resumed.task("impl").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.DISCARDED, AttemptRecord.Outcome.SUCCEEDED);
        assertThat(resumed.appliedChanges()).singleElement().satisfies(applied -> {
            assertThat(applied.id()).isNotEqualTo(crashed.pending.id());
            assertThat(crashed.harness.workspace.hash(FEATURE)).hasValue(applied.files().get(0).postHash());
            assertThat(crashed.harness.workspace.hash(HELPER)).hasValue(applied.files().get(1).postHash());
        });
    }

    @Test
    void cancelAfterACrashBeforeAnythingWasWrittenRestoresTheBaseline() throws Exception {
        Crashed crashed = crashedWhileWriting();
        Files.delete(crashed.harness.workspace.root().resolve(FEATURE));
        Files.delete(crashed.harness.workspace.root().resolve(HELPER));

        WorkflowRun resumed = EngineHarness.restore(crashed.stale);
        assertThat(crashed.harness.stop(resumed, crashed.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).doesNotContain("manual intervention");
        assertThat(crashed.harness.events(EventType.CHANGESET_RECOVERED, "impl")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("diskTouched", false));
        assertThat(resumed.appliedChanges()).isEmpty();
        assertThat(crashed.harness.workspace.contentHash()).isEqualTo(crashed.harness.workspace.baselineHash());
    }

    @Test
    void cancelAfterACrashPartWayThroughWritingRestoresTheBaseline() throws Exception {
        Crashed crashed = crashedWhileWriting();
        Files.delete(crashed.harness.workspace.root().resolve(HELPER));

        WorkflowRun resumed = EngineHarness.restore(crashed.stale);
        assertThat(crashed.harness.stop(resumed, crashed.harness.new Snapshots(), "cancelled by operator")).isEqualTo(RunStatus.HALTED);

        assertThat(resumed.statusReason()).doesNotContain("manual intervention");
        assertThat(crashed.harness.events(EventType.CHANGESET_RECOVERED, "impl")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("restored", List.of(FEATURE)));
        assertThat(crashed.harness.workspace.contentHash()).isEqualTo(crashed.harness.workspace.baselineHash());
    }

    @Test
    void crashAfterEverythingWasWrittenLeavesAnAppliedChangeSetThatOrphanRecoveryRollsBack() throws Exception {
        Crashed crashed = crashedWhileWriting();

        WorkflowRun resumed = EngineHarness.restore(crashed.stale);
        crashed.harness.human = request -> Optional.of(HumanResponse.approve("rita", "ok"));
        assertThat(crashed.harness.execute(resumed)).isEqualTo(RunStatus.COMPLETED);

        assertThat(crashed.harness.count(EventType.CHANGESET_RECOVERED)).isZero();
        assertThat(crashed.harness.events(EventType.CHANGESET_ROLLED_BACK, "impl")).singleElement().satisfies(e -> {
            assertThat(e.text("changeSet")).isEqualTo(crashed.pending.id());
            assertThat(e.text("cause")).isEqualTo("orphaned");
        });
        assertThat(resumed.task("impl").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.DISCARDED, AttemptRecord.Outcome.SUCCEEDED);
        assertThat(resumed.appliedChanges()).singleElement().satisfies(applied -> assertThat(applied.id()).isNotEqualTo(crashed.pending.id()));
    }

    @Test
    void writeFailingPartWayIsUndoneBeforeTheAttemptFails() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir, Map.of(LOCKED, "class Locked {}\n"))
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n"),
                        FileChange.edit(LOCKED, "class Locked {}", "class Locked { unlocked }")));
        Path lockedDir = harness.workspace.root().resolve(LOCKED).getParent();
        assumeTrue(Files.getFileStore(lockedDir).supportsFileAttributeView("posix") && permissionsEnforced(lockedDir),
                "needs enforced POSIX directory permissions (not running as root)");
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2));
        AtomicBoolean locked = new AtomicBoolean();
        List<Boolean> featureOnDiskWhenFailed = new CopyOnWriteArrayList<>();
        List<List<AppliedChangeSet>> stackWhenFailed = new CopyOnWriteArrayList<>();
        try {
            RunStatus status = harness.execute(run, checkpointed -> {
                // The write-ahead checkpoint: the directory becomes unwritable after FEATURE's slot was resolved.
                if (!checkpointed.appliedChanges().isEmpty() && locked.compareAndSet(false, true)) {
                    setWritable(lockedDir, false);
                } else if (checkpointed.appliedChanges().isEmpty() && locked.get()) {
                    featureOnDiskWhenFailed.add(harness.workspace.exists(FEATURE));
                    stackWhenFailed.add(checkpointed.appliedChanges());
                    setWritable(lockedDir, true);
                }
            });
            assertThat(status).isEqualTo(RunStatus.COMPLETED);
        } finally {
            setWritable(lockedDir, true);
        }

        assertThat(harness.events(EventType.ATTEMPT_FAILED, "impl")).singleElement()
                .satisfies(e -> assertThat(e.text("kind")).isEqualTo(FailureKind.TRANSIENT.name()));
        assertThat(featureOnDiskWhenFailed).as("written file undone before the checkpoint").first().isEqualTo(false);
        assertThat(stackWhenFailed.getFirst()).isEmpty();
        assertThat(harness.count(EventType.CHANGESET_APPLIED)).isEqualTo(1);
        assertThat(harness.workspace.read(LOCKED)).hasValue("class Locked { unlocked }\n");
        assertThat(harness.workspace.read(FEATURE)).hasValue("class Feature {}\n");
    }

    // ---------------------------------------------------------------- helpers

    /** A process that died after the write-ahead checkpoint: its snapshot lists a change set the disk may lack. */
    private record Crashed(EngineHarness harness, String stale, AppliedChangeSet pending) {
    }

    private Crashed crashedWhileWriting() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n"),
                        FileChange.create(HELPER, "class Helper {}\n")))
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2).withGates(List.of(), List.of("signoff")));
        EngineHarness.Snapshots snapshots = harness.new Snapshots();
        assertThat(harness.execute(run, snapshots)).isEqualTo(RunStatus.AWAITING_HUMAN);

        long applied = harness.firstSeq(EventType.CHANGESET_APPLIED, "impl");
        WorkflowRun beforeApplied = snapshots.latestAt(applied - 1).restore();
        assertThat(beforeApplied.appliedChanges()).as("durable before any file is written").singleElement()
                .satisfies(pending -> assertThat(pending.id()).isEqualTo(run.appliedChanges().getFirst().id()));
        assertThat(beforeApplied.task("impl").status()).isEqualTo(TaskStatus.RUNNING);
        return new Crashed(harness, snapshots.latestAt(applied - 1).json(), beforeApplied.appliedChanges().getFirst());
    }

    private static boolean permissionsEnforced(Path directory) throws Exception {
        setWritable(directory, false);
        try {
            Files.delete(Files.createTempFile(directory, "probe", ".tmp"));
            return false;
        } catch (java.nio.file.AccessDeniedException e) {
            return true;
        } finally {
            setWritable(directory, true);
        }
    }

    private static void setWritable(Path directory, boolean writable) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(writable ? "rwxr-xr-x" : "r-xr-xr-x"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
