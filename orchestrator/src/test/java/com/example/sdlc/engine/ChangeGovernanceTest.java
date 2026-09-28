package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.policy.PolicyDecision;
import com.example.sdlc.workspace.FileChange;

/** Human governance of changes: approvals bind to exactly what was shown, and never bypass policy. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ChangeGovernanceTest {

    private static final String MIGRATION = "src/main/resources/db/migration/V2__add.sql";
    private static final String CONTROLLER = "src/main/java/demo/web/LinkController.java";
    private static final String CONTROLLER_SOURCE = "package demo.web;\n\nclass LinkController {\n    int limit = 10;\n}\n";

    @TempDir
    Path tempDir;

    @Test
    void requestedChangesOnAChangeSetAreNeverAppliedAndTheNextAttemptAsksAgain() throws Exception {
        List<HumanRequest> asked = new CopyOnWriteArrayList<>();
        List<Boolean> appliedWhenAsked = new CopyOnWriteArrayList<>();
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("migrate", ctx -> change("add column", FileChange.create(MIGRATION,
                        ctx.feedback().isEmpty() ? "ALTER TABLE t ADD COLUMN c INT NOT NULL;\n" : "ALTER TABLE t ADD COLUMN c INT;\n")));
        harness.human = request -> {
            asked.add(request);
            appliedWhenAsked.add(harness.workspace.exists(MIGRATION));
            return Optional.of(asked.size() == 1 ? HumanResponse.requestChanges("rita", "make the column nullable", null)
                    : HumanResponse.approve("rita", "ok"));
        };
        WorkflowRun run = harness.newRun(changeTask("migrate", "migrate", 2));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(asked).hasSize(2).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo(HumanRequest.Kind.CHANGE_APPROVAL);
            assertThat(r.policyRules()).containsExactly("CC-02");
        });
        assertThat(asked).extracting(HumanRequest::attempt).containsExactly(1, 2);
        assertThat(appliedWhenAsked).containsExactly(false, false);
        assertThat(run.task("migrate").feedback()).first().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FailureKind.CHANGES_REQUESTED);
            assertThat(f.message()).contains("make the column nullable");
        });
        assertThat(harness.events(EventType.ATTEMPT_FAILED, "migrate")).singleElement()
                .satisfies(e -> assertThat(e.text("kind")).isEqualTo("CHANGES_REQUESTED"));
        assertThat(harness.executions("migrate")).isEqualTo(2);
        assertThat(harness.count(EventType.CHANGESET_APPLIED)).isEqualTo(1);
        assertThat(harness.workspace.read(MIGRATION)).hasValue("ALTER TABLE t ADD COLUMN c INT;\n");
        assertThat(run.humanRequests()).extracting(HumanRequest::status)
                .containsExactly(HumanRequest.Status.APPLIED, HumanRequest.Status.APPLIED);
    }

    @Test
    void approvedChangeIsReEvaluatedByPolicyBeforeItIsApplied() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("migrate", ctx -> change("add column", FileChange.create(MIGRATION, "ALTER TABLE t ADD COLUMN c INT;\n")));
        WorkflowRun run = harness.newRun(changeTask("migrate", "migrate", 1));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest request = run.pendingHumanRequests().getFirst();

        run.answer(request.id(), HumanResponse.approve("alice", "additive"), Instant.now());
        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        List<ExecutionEvent> governance = harness.log.events().stream()
                .filter(e -> "migrate".equals(e.taskId()))
                .filter(e -> e.type() == EventType.POLICY_EVALUATED || e.type() == EventType.CHANGESET_APPLIED
                        || e.type() == EventType.HUMAN_INPUT_REQUESTED || e.type() == EventType.HUMAN_INPUT_RECEIVED)
                .toList();
        assertThat(governance).extracting(ExecutionEvent::type).containsExactly(EventType.POLICY_EVALUATED,
                EventType.HUMAN_INPUT_REQUESTED, EventType.HUMAN_INPUT_RECEIVED, EventType.POLICY_EVALUATED, EventType.CHANGESET_APPLIED);
        assertThat(governance.get(0).data()).containsEntry("humanApproved", false).containsEntry("decision",
                PolicyDecision.REQUIRE_APPROVAL);
        assertThat(governance.get(3).data()).containsEntry("humanApproved", true);
        assertThat(run.artifacts().current("approval/" + request.id()).orElseThrow().content().path("changeFingerprint").asString())
                .isNotBlank();
    }

    @Test
    void approvalOfAProposalWhosePostImageChangedSinceItWasShownAsksAgainInsteadOfApplying() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir, Map.of(CONTROLLER, CONTROLLER_SOURCE))
                .changeCapability("tune", ctx -> change("raise limit", FileChange.edit(CONTROLLER, "int limit = 10;", "int limit = 20;")));
        WorkflowRun run = harness.newRun(changeTask("tune", "tune", 1));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest shown = run.pendingHumanRequests().getFirst();
        assertThat(shown.policyRules()).containsExactly("SEC-04");

        // While the approval is pending the file moves on (no base hash, so the edit still applies) - the
        // change that would now be applied is not the one the reviewer saw.
        String moved = CONTROLLER_SOURCE.replace("}\n", "    // hotfix applied elsewhere\n}\n");
        Files.writeString(harness.workspace.root().resolve(CONTROLLER), moved);
        run.answer(shown.id(), HumanResponse.approve("alice", "fine"), Instant.now());

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);

        assertThat(harness.count(EventType.CHANGESET_APPLIED)).isZero();
        assertThat(harness.workspace.read(CONTROLLER)).hasValue(moved);
        assertThat(harness.log.events()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(EventType.POLICY_EVALUATED);
            assertThat(e.data()).containsEntry("humanApproved", true);
        });
        assertThat(run.humanRequest(shown.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.APPLIED);
        assertThat(run.pendingHumanRequests()).singleElement().satisfies(again -> {
            assertThat(again.id()).isNotEqualTo(shown.id());
            assertThat(again.kind()).isEqualTo(HumanRequest.Kind.CHANGE_APPROVAL);
            assertThat(again.details()).first().asString().contains("changed after the previous approval");
            assertThat(again.details()).contains("EDIT " + CONTROLLER);
        });
        assertThat(run.task("tune").status()).isEqualTo(TaskStatus.AWAITING_HUMAN);
    }

    @Test
    void answersThatDoNotFitTheCheckpointAreRejectedByTheRun() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("work", ctx -> output("out/" + ctx.task().id(), 1))
                .gate("signoff", ctx -> GateResult.approval("sign off " + ctx.task().id(), List.of()))
                .capability("reviewed", List.of("signoff"), null, ctx -> output("reviewed", 1))
                .changeCapability("migrate", ctx -> change("add column", FileChange.create(MIGRATION, "ALTER TABLE t ADD COLUMN c INT;\n")));
        WorkflowRun run = harness.newRun(task("upstream", "work"), task("unrelated", "work"),
                task("review", "reviewed", "upstream"), changeTask("migrate", "migrate", 1, "upstream"));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest gate = pendingFor(run, "review");
        HumanRequest changeSet = pendingFor(run, "migrate");

        assertThatThrownBy(() -> run.answer(gate.id(), HumanResponse.requestChanges("ann", "redo", "unrelated"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'unrelated' is not review or one of its prerequisites");
        assertThatThrownBy(() -> run.answer(gate.id(), HumanResponse.requestChanges("ann", "redo", "ghost"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'ghost' is not review");
        assertThatThrownBy(() -> run.answer(changeSet.id(), HumanResponse.requestChanges("ann", "redo", "upstream"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can only send its own task (migrate) back");
        assertThat(run.pendingHumanRequests()).hasSize(2);

        run.answer(gate.id(), HumanResponse.requestChanges("ann", "redo", "upstream"), Instant.now());
        run.answer(changeSet.id(), HumanResponse.requestChanges("ann", "redo", "migrate"), Instant.now());
        assertThat(run.humanRequests()).extracting(HumanRequest::status).containsOnly(HumanRequest.Status.ANSWERED);
    }

    @Test
    void invalidAnswerFromAnImmediateReviewerIsIgnoredAndTheCheckpointStaysOpen() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("work", ctx -> output("out/" + ctx.task().id(), 1))
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("reviewed", List.of("signoff"), null, ctx -> output("reviewed", 1));
        harness.human = request -> Optional.of(HumanResponse.requestChanges("ann", "redo the other thing", "unrelated"));
        WorkflowRun run = harness.newRun(task("upstream", "work"), task("unrelated", "work"), task("review", "reviewed", "upstream"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);

        assertThat(harness.events(EventType.HUMAN_INPUT_RECEIVED, "review")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("ignored", true));
        assertThat(pendingFor(run, "review").status()).isEqualTo(HumanRequest.Status.PENDING);
        assertThat(run.task("unrelated").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(harness.executions("unrelated")).isEqualTo(1);
        assertThat(harness.count(EventType.TASK_INVALIDATED)).isZero();
        assertThat(run.replans()).isZero();
    }

    @Test
    void gateApprovalDoesNotCompleteATaskOnATreeThatChangedWhileWaiting() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create("src/main/java/demo/Feature.java", "class Feature {}\n")))
                .standardGate("workspace-integrity")
                .gate("signoff", ctx -> GateResult.approval("release sign-off for " + ctx.workspace().contentHash(), List.of()))
                .capability("release", List.of("workspace-integrity"), List.of("signoff"), null, ctx -> output("release", "assessed"));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1), task("release", "release", "impl").withMaxAttempts(2));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest signoff = run.pendingHumanRequests().getFirst();
        assertThat(signoff.kind()).isEqualTo(HumanRequest.Kind.GATE_APPROVAL);

        Files.writeString(harness.workspace.root().resolve("src/main/java/demo/Sneaky.java"), "class Sneaky {}\n");
        run.answer(signoff.id(), HumanResponse.approve("vic", "ship it"), Instant.now());

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertThat(harness.events(EventType.TASK_SUCCEEDED, "release")).isEmpty();
        assertThat(run.artifacts().current("approval/" + signoff.id())).isEmpty();
        assertThat(run.artifacts().current("release")).isEmpty();
        assertThat(harness.events(EventType.ATTEMPT_FAILED, "release")).first().satisfies(e -> {
            assertThat(e.text("kind")).isEqualTo("CONFLICT");
            assertThat(e.message()).contains("workspace changed while waiting for signoff approval");
        });
        // The retry is stopped by the integrity entry gate, which names the out-of-band edit.
        assertThat(harness.events(EventType.GATE_FAILED, "release")).singleElement().satisfies(e -> {
            assertThat(e.text("gate")).isEqualTo("workspace-integrity");
            assertThat(e.text("phase")).isEqualTo("entry");
            assertThat(e.data().get("details")).asString().contains("src/main/java/demo/Sneaky.java was changed outside governance");
        });
        assertThat(run.task("release").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(harness.executions("release")).isEqualTo(1);
    }

    private static HumanRequest pendingFor(WorkflowRun run, String taskId) {
        return run.pendingHumanRequests().stream().filter(r -> r.taskId().equals(taskId)).findFirst().orElseThrow();
    }
}
