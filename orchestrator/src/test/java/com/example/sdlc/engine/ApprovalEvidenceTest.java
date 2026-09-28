package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.workspace.FileChange;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

import tools.jackson.databind.JsonNode;

/**
 * A change approval is backed by durable evidence: the complete diff of exactly the proposal the approval's
 * fingerprint binds to, kept as an artifact and written next to the run.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ApprovalEvidenceTest {

    private static final String MIGRATION = "src/main/resources/db/migration/V2__add.sql";
    private static final String CONTROLLER = "src/main/java/demo/web/LinkController.java";
    private static final String CONTROLLER_SOURCE = "package demo.web;\n\nclass LinkController {\n    int limit = 10;\n}\n";
    private static final String CONTROLLER_AFTER = "package demo.web;\n\nclass LinkController {\n    int limit = 20;\n}\n";
    private static final String MIGRATION_SOURCE = "ALTER TABLE t ADD COLUMN c INT;\n";

    @TempDir
    Path tempDir;

    private EngineHarness harness() throws Exception {
        return new EngineHarness(tempDir, Map.of(CONTROLLER, CONTROLLER_SOURCE))
                .changeCapability("tune", ctx -> change("raise limit and add column",
                        FileChange.edit(CONTROLLER, "int limit = 10;", "int limit = 20;"),
                        FileChange.create(MIGRATION, MIGRATION_SOURCE)));
    }

    @Test
    void persistedDiffReproducesExactlyTheFingerprintedProposal() throws Exception {
        EngineHarness harness = harness();
        RunStore store = new RunStore(tempDir.resolve("runs"));
        List<Boolean> patchOnDiskWhenAsked = new CopyOnWriteArrayList<>();
        harness.human = request -> {
            patchOnDiskWhenAsked.add(Files.isRegularFile(patchFile(store, request.id())));
            return Optional.empty();
        };
        WorkflowRun run = harness.newRun(changeTask("tune", "tune", 1));

        assertThat(harness.execute(run, store::save)).isEqualTo(RunStatus.AWAITING_HUMAN);

        HumanRequest request = run.pendingHumanRequests().getFirst();
        assertThat(patchOnDiskWhenAsked).as("durable before the reviewer is asked").containsExactly(true);
        Artifact review = run.artifacts().current(HumanRequest.CHANGE_REVIEW_PREFIX + request.id()).orElseThrow();
        assertThat(review.producer()).isEqualTo("engine");
        assertThat(review.kind()).isEqualTo("change-diff");
        JsonNode content = review.content();
        assertThat(content.path("requestId").asString()).isEqualTo(request.id());
        assertThat(content.path("taskId").asString()).isEqualTo("tune");
        assertThat(content.path("generation").asInt()).isEqualTo(1);
        assertThat(content.path("attempt").asInt()).isEqualTo(1);
        String parkedFingerprint = run.task("tune").parked().approvalFingerprint();
        assertThat(content.path("fingerprint").asString()).isEqualTo(parkedFingerprint);
        assertThat(content.path("files")).extracting(f -> f.path("op").asString() + " " + f.path("path").asString())
                .containsExactly("EDIT " + CONTROLLER, "CREATE " + MIGRATION);

        String diff = content.path("diff").asString();
        assertThat(diff).contains("-    int limit = 10;").contains("+    int limit = 20;");
        Map<String, String> before = new LinkedHashMap<>();
        before.put(CONTROLLER, CONTROLLER_SOURCE);
        before.put(MIGRATION, null);
        assertThat(apply(diff, before)).containsExactlyInAnyOrderEntriesOf(Map.of(CONTROLLER, CONTROLLER_AFTER, MIGRATION, MIGRATION_SOURCE));
        assertThat(Files.readString(patchFile(store, request.id()))).isEqualTo(diff);

        assertThat(request.details()).contains("Complete diff: approvals/" + request.id() + ".patch in the run directory");
        assertThat(request.details()).contains("-     int limit = 10;", "+     int limit = 20;", "+ " + MIGRATION_SOURCE.strip());
        assertThat(harness.workspace.read(CONTROLLER)).hasValue(CONTROLLER_SOURCE);

        run.answer(request.id(), HumanResponse.approve("alice", "reviewed the patch"), Instant.now());
        assertThat(harness.execute(run, store::save)).isEqualTo(RunStatus.COMPLETED);

        assertThat(run.artifacts().current("approval/" + request.id()).orElseThrow().content().path("changeFingerprint").asString())
                .isEqualTo(parkedFingerprint);
        // What was applied is exactly what the diff described.
        assertThat(harness.workspace.read(CONTROLLER)).hasValue(CONTROLLER_AFTER);
        assertThat(harness.workspace.read(MIGRATION)).hasValue(MIGRATION_SOURCE);
    }

    @Test
    void proposalThatChangedAfterReviewGetsANewRequestWithNewEvidenceAndTheOldApprovalIsNotApplied() throws Exception {
        EngineHarness harness = harness();
        RunStore store = new RunStore(tempDir.resolve("runs"));
        WorkflowRun run = harness.newRun(changeTask("tune", "tune", 1));
        assertThat(harness.execute(run, store::save)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest shown = run.pendingHumanRequests().getFirst();
        JsonNode shownEvidence = run.artifacts().current(HumanRequest.CHANGE_REVIEW_PREFIX + shown.id()).orElseThrow().content();
        String shownPatch = Files.readString(patchFile(store, shown.id()));

        String moved = CONTROLLER_SOURCE.replace("}\n", "    // hotfix applied elsewhere\n}\n");
        Files.writeString(harness.workspace.root().resolve(CONTROLLER), moved);
        run.answer(shown.id(), HumanResponse.approve("alice", "fine"), Instant.now());

        assertThat(harness.execute(run, store::save)).isEqualTo(RunStatus.AWAITING_HUMAN);

        assertThat(harness.count(EventType.CHANGESET_APPLIED)).isZero();
        assertThat(harness.workspace.read(CONTROLLER)).hasValue(moved);
        assertThat(harness.workspace.exists(MIGRATION)).isFalse();
        HumanRequest again = run.pendingHumanRequests().getFirst();
        assertThat(again.id()).isNotEqualTo(shown.id());
        JsonNode newEvidence = run.artifacts().current(HumanRequest.CHANGE_REVIEW_PREFIX + again.id()).orElseThrow().content();
        assertThat(newEvidence.path("fingerprint").asString())
                .isNotEqualTo(shownEvidence.path("fingerprint").asString())
                .isEqualTo(run.task("tune").parked().approvalFingerprint());
        assertThat(newEvidence.path("diff").asString()).isNotEqualTo(shownEvidence.path("diff").asString());
        Map<String, String> movedBefore = new LinkedHashMap<>();
        movedBefore.put(CONTROLLER, moved);
        movedBefore.put(MIGRATION, null);
        assertThat(apply(newEvidence.path("diff").asString(), movedBefore)).containsExactlyInAnyOrderEntriesOf(
                Map.of(CONTROLLER, moved.replace("int limit = 10;", "int limit = 20;"), MIGRATION, MIGRATION_SOURCE));
        assertThat(Files.readString(patchFile(store, again.id()))).isEqualTo(newEvidence.path("diff").asString());
        assertThat(Files.readString(patchFile(store, shown.id()))).as("earlier evidence is kept as it was").isEqualTo(shownPatch);
        assertThat(again.details()).contains("Complete diff: approvals/" + again.id() + ".patch in the run directory");
    }

    @Test
    void runStoreKeepsEachApprovalPatchEqualToItsEvidenceAndOnlyUnderTheRunDirectory() throws Exception {
        EngineHarness harness = harness();
        RunStore store = new RunStore(tempDir.resolve("runs"));
        WorkflowRun run = harness.newRun(changeTask("tune", "tune", 1));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest request = run.pendingHumanRequests().getFirst();
        run.artifacts().publish(HumanRequest.CHANGE_REVIEW_PREFIX + "../../escape", "change-diff",
                Json.tree(Map.of("diff", "not a request")), "engine", 0, List.of(), Instant.now());

        store.save(run);

        Path patch = patchFile(store, request.id());
        assertThat(patch).isRegularFile();
        assertThat(Files.readString(patch)).isEqualTo(
                run.artifacts().current(HumanRequest.CHANGE_REVIEW_PREFIX + request.id()).orElseThrow().content().path("diff").asString());
        try (var files = Files.list(patch.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(request.id() + ".patch");
        }
        assertThat(tempDir.resolve("escape.patch")).doesNotExist();
        assertThat(tempDir.resolve("runs/escape.patch")).doesNotExist();

        // The artifact is the source of truth: a patch that no longer matches it is replaced.
        Files.writeString(patch, "stale");
        store.save(run);
        assertThat(Files.readString(patch)).isEqualTo(
                run.artifacts().current(HumanRequest.CHANGE_REVIEW_PREFIX + request.id()).orElseThrow().content().path("diff").asString());
        assertThat(store.load(run.id()).humanRequest(request.id()).orElseThrow().details())
                .contains("Complete diff: approvals/" + request.id() + ".patch in the run directory");
    }

    @Test
    void requestIdReissuedAfterALostCheckpointGetsThePatchOfItsOwnProposal() throws Exception {
        AtomicInteger limit = new AtomicInteger(20);
        EngineHarness harness = new EngineHarness(tempDir, Map.of(CONTROLLER, CONTROLLER_SOURCE))
                .changeCapability("tune", ctx -> change("raise limit",
                        FileChange.edit(CONTROLLER, "int limit = 10;", "int limit = " + limit.get() + ";")));
        RunStore store = new RunStore(tempDir.resolve("runs"));
        WorkflowRun first = harness.newRun(changeTask("tune", "tune", 1));
        String beforeTheRequest = EngineHarness.snapshot(first);
        assertThat(harness.execute(first, store::save)).isEqualTo(RunStatus.AWAITING_HUMAN);
        String requestId = first.pendingHumanRequests().getFirst().id();
        assertThat(Files.readString(patchFile(store, requestId))).contains("+    int limit = 20;");
        // The save wrote the patch and the process died before run.json was replaced; the restart reissues the same
        // id for a different proposal (reasoning is not deterministic across processes).
        WorkflowRun resumed = EngineHarness.restore(beforeTheRequest);
        limit.set(30);

        assertThat(harness.execute(resumed, store::save)).isEqualTo(RunStatus.AWAITING_HUMAN);

        HumanRequest reissued = resumed.pendingHumanRequests().getFirst();
        assertThat(reissued.id()).isEqualTo(requestId);
        String diff = resumed.artifacts().current(HumanRequest.CHANGE_REVIEW_PREFIX + requestId).orElseThrow().content()
                .path("diff").asString();
        assertThat(diff).contains("+    int limit = 30;");
        assertThat(Files.readString(patchFile(store, requestId))).isEqualTo(diff);
    }

    @Test
    void excerptShowsTheRemovalOfOneOfSeveralIdenticalLines() throws Exception {
        String guarded = "package demo.web;\n\nclass LinkController {\n    void delete() {\n        requireAdmin();\n"
                + "        requireAdmin();\n        remove();\n    }\n}\n";
        EngineHarness harness = new EngineHarness(tempDir, Map.of(CONTROLLER, guarded))
                .changeCapability("tune", ctx -> change("drop a duplicated check",
                        FileChange.edit(CONTROLLER, "        requireAdmin();\n        requireAdmin();\n", "        requireAdmin();\n")));
        WorkflowRun run = harness.newRun(changeTask("tune", "tune", 1));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);

        HumanRequest request = run.pendingHumanRequests().getFirst();
        assertThat(request.details()).contains("--- " + CONTROLLER + " (1 removed, 0 added line(s))", "-         requireAdmin();");
        assertThat(request.details()).noneMatch(line -> line.startsWith("+ "));
    }

    // ---------------------------------------------------------------- helpers

    private static Path patchFile(RunStore store, String requestId) {
        return store.directory("run-1").resolve(HumanRequest.approvalPatchPath(requestId));
    }

    /** Applies each file section of a unified diff to its before-image (null: absent); returns the after-images. */
    private static Map<String, String> apply(String diff, Map<String, String> before) throws Exception {
        Map<String, String> after = new LinkedHashMap<>();
        List<List<String>> sections = new ArrayList<>();
        for (String line : diff.lines().toList()) {
            if (line.startsWith("diff --git ")) {
                sections.add(new ArrayList<>());
            }
            sections.getLast().add(line);
        }
        for (List<String> section : sections) {
            String path = section.getFirst().substring(section.getFirst().indexOf(" b/") + 3);
            String original = before.get(path);
            List<String> patched = DiffUtils.patch(original == null ? List.of() : original.lines().toList(),
                    UnifiedDiffUtils.parseUnifiedDiff(section));
            after.put(path, String.join("\n", patched) + "\n");
        }
        return after;
    }
}
