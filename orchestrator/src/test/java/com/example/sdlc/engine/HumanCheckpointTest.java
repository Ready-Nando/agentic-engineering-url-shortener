package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.await;
import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.task;
import static com.example.sdlc.engine.EngineHarness.verifyTask;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.workspace.FileChange;

import tools.jackson.databind.JsonNode;

/** Clarifications and the life cycle of checkpoints that are answered, withdrawn or cancelled. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HumanCheckpointTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";

    @TempDir
    Path tempDir;

    private static HumanRequest.Question question(String id, String text) {
        return new HumanRequest.Question(id, text, "", List.of(), true);
    }

    private static Map<String, String> clarifications(TaskContext ctx) {
        Map<String, String> answers = new LinkedHashMap<>();
        ctx.find(ArtifactKeys.CLARIFICATIONS, JsonNode.class)
                .ifPresent(c -> c.path("answers").properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString())));
        return answers;
    }

    @Test
    void twoRoundClarificationAccumulatesAnswersAndTheFinalAttemptUsesTheirValues() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("unambiguous", ctx -> {
                    Map<?, ?> spec = ctx.output("spec", Map.class).orElseThrow();
                    if (spec.get("expiredStatus") == null) {
                        return GateResult.clarification("Expired links?", List.of(), List.of(question("Q1", "Status for expired links?")));
                    }
                    if (spec.get("retentionDays") == null) {
                        return GateResult.clarification("Retention?", List.of(), List.of(question("Q2", "Keep expired links for how long?")));
                    }
                    return GateResult.pass("clear");
                })
                .capability("analyse", List.of("unambiguous"), null, ctx -> {
                    Map<String, String> answers = clarifications(ctx);
                    Map<String, Object> spec = new LinkedHashMap<>();
                    spec.put("expiredStatus", answers.containsKey("Q1") ? Integer.valueOf(answers.get("Q1")) : null);
                    spec.put("retentionDays", answers.containsKey("Q2") ? Integer.valueOf(answers.get("Q2")) : null);
                    return TaskResult.of("spec", "spec", OutputArtifact.of("spec", spec));
                })
                .capability("design", ctx -> {
                    Map<?, ?> spec = ctx.read("spec", Map.class);
                    return output("design", "GET /{code} -> " + spec.get("expiredStatus") + " after " + spec.get("retentionDays") + " days");
                });
        WorkflowRun run = harness.newRun(task("analyse", "analyse"), task("design", "design", "analyse"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest first = run.pendingHumanRequests().getFirst();
        assertThat(first.questions()).extracting(HumanRequest.Question::id).containsExactly("Q1");
        run.answer(first.id(), HumanResponse.answer("dana", Map.of("Q1", "410")), Instant.now());

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest second = run.pendingHumanRequests().getFirst();
        assertThat(second.questions()).extracting(HumanRequest.Question::id).containsExactly("Q2");
        run.answer(second.id(), HumanResponse.answer("dana", Map.of("Q2", "30")), Instant.now());

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        Artifact clarifications = run.artifacts().current(ArtifactKeys.CLARIFICATIONS).orElseThrow();
        assertThat(clarifications.ref()).hasToString(ArtifactKeys.CLARIFICATIONS + "@v2");
        assertThat(clarifications.content().path("answers").path("Q1").asString()).isEqualTo("410");
        assertThat(clarifications.content().path("answers").path("Q2").asString()).isEqualTo("30");
        assertThat(run.task("analyse").inputs()).extracting(Object::toString).containsExactly(ArtifactKeys.CLARIFICATIONS + "@v2");
        assertThat(run.artifacts().current("spec").orElseThrow().content().path("expiredStatus").asInt()).isEqualTo(410);
        assertThat(run.artifacts().current("design").orElseThrow().content().path("value").asString())
                .isEqualTo("GET /{code} -> 410 after 30 days");
        assertThat(harness.executions("analyse")).isEqualTo(3);
        assertThat(harness.executions("design")).isEqualTo(1);
    }

    @Test
    void pendingCheckpointWithdrawnByInvalidationIsCancelledWithItsRequestId() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir);
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2),
                task("docs", "docs", "impl"),
                verifyTask("verify", "verify", List.of("impl"), "impl"));
        CountDownLatch docsParked = harness.signalOn(e -> e.type() == EventType.HUMAN_INPUT_REQUESTED && "docs".equals(e.taskId()));
        harness.changeCapability("implement", ctx -> change("attempt " + ctx.attempt(),
                        FileChange.create(FEATURE, "class Feature { int v = " + (ctx.feedback().isEmpty() ? 41 : 42) + "; }\n")))
                .gate("docs-approval", ctx -> GateResult.approval("approve docs", List.of()))
                .capability("docs", List.of("docs-approval"), null, ctx -> output("docs", ctx.readAll(ArtifactKeys.CHANGES_PREFIX).size()))
                .gate("tests-pass", ctx -> ctx.workspace().read(FEATURE).orElse("").contains("42") ? GateResult.pass("ok")
                        : GateResult.defect("FeatureTest failed", List.of(), List.of()))
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    if (ctx.invocation() == 1) {
                        await(docsParked, "docs to park at its approval gate");
                    }
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("verification", "ran");
                });

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);

        List<HumanRequest> requests = run.humanRequests();
        assertThat(requests).hasSize(2).allSatisfy(r -> assertThat(r.taskId()).isEqualTo("docs"));
        HumanRequest withdrawn = requests.getFirst();
        assertThat(withdrawn.status()).isEqualTo(HumanRequest.Status.CANCELLED);
        assertThat(harness.events(EventType.HUMAN_INPUT_CANCELLED, "docs")).singleElement().satisfies(e -> {
            assertThat(e.text("request")).isEqualTo(withdrawn.id());
            assertThat(e.text("reason")).contains("consumed output of invalidated work");
            assertThat(e.seq()).isGreaterThan(harness.firstSeq(EventType.REWORK_REQUESTED, "verify"));
        });
        assertThat(run.pendingHumanRequests()).singleElement().satisfies(r -> assertThat(r.id()).isNotEqualTo(withdrawn.id()));
        assertThat(run.task("docs").parked().requestId()).isEqualTo(run.pendingHumanRequests().getFirst().id());
        assertThat(run.task("docs").parked().result().inputs()).extracting(Object::toString).containsExactly("changes/impl@v2");
        assertThat(harness.executions("docs")).isEqualTo(2);
    }

    @Test
    void answeredCheckpointWithdrawnByAnEarlierAnswerIsCancelledNotApplied() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir);
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 1),
                task("review", "reviewed", "impl"), task("docs", "reviewed", "impl"));
        CountDownLatch reviewParked = harness.signalOn(e -> e.type() == EventType.HUMAN_INPUT_REQUESTED && "review".equals(e.taskId()));
        harness.changeCapability("implement", ctx -> change("feature " + ctx.invocation(),
                        FileChange.create(FEATURE, "class Feature { /* " + ctx.invocation() + " */ }\n")))
                .gate("signoff", ctx -> GateResult.approval("sign off " + ctx.task().id(), List.of()))
                .capability("reviewed", List.of("signoff"), null, ctx -> {
                    if (ctx.task().id().equals("docs") && ctx.invocation() == 1) {
                        // Park after review, so review's request is answered (and applied) first on resume.
                        await(reviewParked, "review to park");
                    }
                    return output(ctx.task().id(), ctx.readAll(ArtifactKeys.CHANGES_PREFIX).size());
                });
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest reviewRequest = run.humanRequests().get(0);
        HumanRequest docsRequest = run.humanRequests().get(1);
        assertThat(List.of(reviewRequest.taskId(), docsRequest.taskId())).containsExactly("review", "docs");

        run.answer(reviewRequest.id(), HumanResponse.requestChanges("ann", "rename the class", "impl"), Instant.now());
        run.answer(docsRequest.id(), HumanResponse.approve("ann", "docs are fine"), Instant.now());
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);

        assertThat(run.humanRequest(reviewRequest.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.APPLIED);
        assertThat(run.humanRequest(docsRequest.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.CANCELLED);
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.HUMAN_INPUT_RECEIVED)
                .extracting(e -> e.text("request")).containsExactly(reviewRequest.id());
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.HUMAN_INPUT_CANCELLED)
                .extracting(e -> e.text("request")).containsExactly(docsRequest.id());
        assertThat(run.artifacts().current("approval/" + docsRequest.id())).isEmpty();
        assertThat(harness.events(EventType.TASK_SUCCEEDED, "docs")).isEmpty();
        assertThat(harness.executions("docs")).isEqualTo(2);
        assertThat(run.pendingHumanRequests()).extracting(HumanRequest::taskId).containsExactlyInAnyOrder("review", "docs");
    }

    @Test
    void answeredCheckpointWhoseTaskNoLongerWaitsIsLoggedAsNotApplied() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("reviewed", List.of("signoff"), null, ctx -> output("reviewed", ctx.invocation()));
        WorkflowRun run = harness.newRun(task("review", "reviewed"));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest request = run.pendingHumanRequests().getFirst();
        run.answer(request.id(), HumanResponse.approve("ann", "ok"), Instant.now());
        // The checkpoint is withdrawn between the answer and the resume (as an invalidation would do).
        run.task("review").clearParked();
        run.task("review").status(TaskStatus.PENDING);

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);

        assertThat(run.humanRequest(request.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.CANCELLED);
        assertThat(harness.events(EventType.HUMAN_INPUT_CANCELLED, "review")).singleElement().satisfies(e -> {
            assertThat(e.text("request")).isEqualTo(request.id());
            assertThat(e.message()).contains("not applied");
        });
        assertThat(harness.count(EventType.HUMAN_INPUT_RECEIVED)).isZero();
        assertThat(run.artifacts().current("approval/" + request.id())).isEmpty();
        assertThat(run.pendingHumanRequests()).singleElement().satisfies(r -> assertThat(r.id()).isNotEqualTo(request.id()));
    }

    @Test
    void answeredCheckpointsLeftBehindByASafeStopAreCancelled() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("signoff", ctx -> GateResult.approval("sign off " + ctx.task().id(), List.of()))
                .capability("reviewed", List.of("signoff"), null, ctx -> output(ctx.task().id(), 1));
        harness.settings = new WorkflowEngine.Settings(1, 6, 2);
        WorkflowRun run = harness.newRun(task("first", "reviewed"), task("second", "reviewed"));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest first = run.humanRequests().get(0);
        HumanRequest second = run.humanRequests().get(1);
        run.answer(first.id(), HumanResponse.reject("ann", "not this quarter"), Instant.now());
        run.answer(second.id(), HumanResponse.approve("ann", "ok"), Instant.now());

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        // The rejection stops the run before the second answer is applied: that answer must not be left
        // looking like it is still about to take effect.
        assertThat(run.humanRequest(first.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.APPLIED);
        assertThat(run.humanRequest(second.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.CANCELLED);
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.HUMAN_INPUT_CANCELLED).singleElement().satisfies(e -> {
            assertThat(e.text("request")).isEqualTo(second.id());
            assertThat(e.message()).contains("not applied");
        });
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.HUMAN_INPUT_RECEIVED)
                .extracting(e -> e.text("request")).containsExactly(first.id());
        assertThat(run.humanRequests()).noneMatch(r -> r.status() == HumanRequest.Status.ANSWERED
                || r.status() == HumanRequest.Status.PENDING);
        assertThat(run.task("second").status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void stoppingAPausedRunCancelsAnAnswerThatWasRecordedButNeverApplied() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("reviewed", List.of("signoff"), null, ctx -> output("reviewed", 1));
        WorkflowRun run = harness.newRun(task("review", "reviewed"));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest request = run.pendingHumanRequests().getFirst();
        run.answer(request.id(), HumanResponse.approve("ann", "ok"), Instant.now());

        assertThat(harness.engine().stop(run, harness.workspace, harness.log, r -> { }, "operator changed their mind"))
                .isEqualTo(RunStatus.HALTED);

        assertThat(run.humanRequest(request.id()).orElseThrow().status()).isEqualTo(HumanRequest.Status.CANCELLED);
        assertThat(harness.events(EventType.HUMAN_INPUT_CANCELLED, "review")).singleElement()
                .satisfies(e -> assertThat(e.text("request")).isEqualTo(request.id()));
        assertThat(run.task("review").status()).isEqualTo(TaskStatus.CANCELLED);
    }
}
