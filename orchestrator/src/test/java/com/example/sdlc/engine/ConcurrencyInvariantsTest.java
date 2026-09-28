package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.await;
import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.refs;
import static com.example.sdlc.engine.EngineHarness.task;
import static com.example.sdlc.engine.EngineHarness.verifyTask;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.FileChange;

/**
 * Scheduling invariants that only show up when work overlaps: results computed from inputs that changed
 * underneath them, eligibility while upstream work is redone, and reproducible event order.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ConcurrencyInvariantsTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";

    @TempDir
    Path tempDir;

    private static GateResult featureAnswers42(GateContext ctx) {
        return ctx.workspace().read(FEATURE).orElse("").contains("42") ? GateResult.pass("all tests passed")
                : GateResult.defect("FeatureTest failed", List.of("expected 42 but was 41"), List.of());
    }

    private static TaskResult feature(TaskContext ctx) {
        return change("attempt " + ctx.attempt(), FileChange.create(FEATURE,
                "class Feature { int answer() { return " + (ctx.feedback().isEmpty() ? 41 : 42) + "; } }\n"));
    }

    @Test
    void siblingStillRunningWhenReworkRetractsItsInputIsDiscardedAndRerunsOnCurrentInputs() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir);
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2),
                verifyTask("verify", "verify", List.of("impl"), "impl"),
                task("review", "review", "impl"),
                task("release", "release", "verify", "review"));
        CountDownLatch reworkRequested = harness.signalOn(e -> e.type() == EventType.REWORK_REQUESTED);
        harness.changeCapability("implement", ConcurrencyInvariantsTest::feature)
                .gate("tests-pass", ConcurrencyInvariantsTest::featureAnswers42)
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("verification", "ran");
                })
                .capability("review", ctx -> {
                    // Reads the change set first, then is still running when the verifier's rework retracts it.
                    List<Artifact> changes = ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    if (ctx.invocation() == 1) {
                        await(reworkRequested, "the verifier to request rework");
                    }
                    return output("review/security", refs(changes));
                })
                .capability("release", ctx -> {
                    ctx.read("verification", Map.class);
                    ctx.read("review/security", Map.class);
                    return output("release", "ok");
                });

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.events(EventType.ATTEMPT_DISCARDED, "review")).singleElement().satisfies(discarded -> {
            assertThat(discarded.attempt()).isEqualTo(1);
            assertThat(discarded.data().get("staleInputs")).isEqualTo(List.of("changes/impl@v1"));
        });
        assertThat(harness.executions("review")).isEqualTo(2);
        assertThat(run.task("review").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.DISCARDED, AttemptRecord.Outcome.SUCCEEDED);
        assertThat(run.task("review").inputs()).extracting(Object::toString).containsExactly("changes/impl@v2");
        assertThat(run.task("review").inputs()).allMatch(run.artifacts()::isCurrent);
        assertThat(run.artifacts().current("review/security").orElseThrow().content().path("value").get(0).asString())
                .isEqualTo("changes/impl@v2");
        assertThat(run.task("release").inputs()).allMatch(run.artifacts()::isCurrent);
    }

    @Test
    void readyTaskIsNotDispatchedWhileAnAncestorIsBeingRedone() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir);
        // Two workers: verify and slow take both slots, so review is READY but waiting when impl is sent back.
        harness.settings = new WorkflowEngine.Settings(2, 6, 2);
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2),
                verifyTask("verify", "verify", List.of("impl"), "impl"),
                task("slow", "slow", "impl"),
                task("review", "review", "impl"),
                task("release", "release", "verify", "slow", "review"));
        CountDownLatch reworkDispatched = harness.signalOn(e -> e.type() == EventType.TASK_STARTED
                && "impl".equals(e.taskId()) && Integer.valueOf(2).equals(e.attempt()));
        CountDownLatch slowCommitted = harness.signalOn(e -> e.type() == EventType.TASK_SUCCEEDED && "slow".equals(e.taskId()));
        harness.changeCapability("implement", ctx -> {
                    if (ctx.attempt() == 2) {
                        // Keep the rework running until slow's slot is free: the next dispatch pass then sees
                        // review READY, a free worker, and its ancestor impl RUNNING.
                        await(slowCommitted, "slow to commit");
                    }
                    return feature(ctx);
                })
                .gate("tests-pass", ConcurrencyInvariantsTest::featureAnswers42)
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("verification", "ran");
                })
                .capability("slow", ctx -> {
                    await(reworkDispatched, "impl to be dispatched for rework");
                    return output("slow", "done");
                })
                .capability("review", ctx -> output("review", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .capability("release", ctx -> output("release", "ok"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        // The scenario really opened the window: slow committed (freeing a worker) while impl was being redone.
        long reworkStarted = harness.lastSeq(EventType.TASK_STARTED, "impl");
        long reworkCommitted = harness.lastSeq(EventType.TASK_SUCCEEDED, "impl");
        assertThat(harness.firstSeq(EventType.TASK_SUCCEEDED, "slow")).isBetween(reworkStarted, reworkCommitted);
        // ... and review still did not start until impl had succeeded again.
        assertThat(harness.startsWhileRedoing("review", "impl")).as("review starts while impl was being redone").isEmpty();
        assertThat(harness.executions("review")).isEqualTo(1);
        assertThat(harness.firstSeq(EventType.TASK_STARTED, "review")).isGreaterThan(reworkCommitted);
        assertThat(run.task("review").inputs()).extracting(Object::toString).containsExactly("changes/impl@v2");
        assertThat(harness.executions("slow")).isEqualTo(1);
    }

    @Test
    void theSameRunTwiceProducesTheSameEventSequence() throws Exception {
        List<String> first = deterministicRun(tempDir.resolve("first"));
        List<String> second = deterministicRun(tempDir.resolve("second"));

        assertThat(first).hasSizeGreaterThan(60)
                .contains("REWORK_REQUESTED verify 1 -", "RETRY_SCHEDULED draft 1 -", "CHANGESET_ROLLED_BACK impl 1 cs-1", "HUMAN_INPUT_RECEIVED release 1 hr-3");
        assertThat(second).isEqualTo(first);
    }

    /**
     * One worker, so the only freedom left is the engine's own choices (dispatch order among six ready tasks,
     * output publication order, ids): a retry, a rework with rollback and a human approval.
     */
    private static List<String> deterministicRun(Path directory) throws Exception {
        EngineHarness harness = new EngineHarness(directory);
        harness.settings = new WorkflowEngine.Settings(1, 6, 2);
        harness.human = request -> Optional.of(HumanResponse.approve("erin", "ok"));
        harness.capability("source", ctx -> TaskResult.of("two outputs", Map.of(
                        "source/b", OutputArtifact.of("test", Map.of("value", "b")),
                        "source/a", OutputArtifact.of("test", Map.of("value", "a")))))
                .capability("leaf", ctx -> {
                    ctx.read("source/a", Map.class);
                    return output("leaf/" + ctx.task().id(), ctx.task().id());
                })
                .gate("draft-ok", ctx -> ctx.state().attempt() == 1 ? GateResult.fail("first draft rejected", List.of("again"))
                        : GateResult.pass("good"))
                .capability("draft", List.of("draft-ok"), null, ctx -> {
                    ctx.read("source/b", Map.class);
                    return output("draft", "same content every time");
                })
                .changeCapability("implement", ctx -> {
                    ctx.read("draft", Map.class);
                    return feature(ctx);
                })
                .gate("tests-pass", ConcurrencyInvariantsTest::featureAnswers42)
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("verification", "ran");
                })
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("release", List.of("signoff"), null, ctx -> output("release", refs(ctx.readAll(""))));
        List<TaskSpec> tasks = new ArrayList<>(List.of(task("source", "source")));
        for (int i = 1; i <= 5; i++) {
            tasks.add(task("leaf" + i, "leaf", "source"));
        }
        tasks.add(task("draft", "draft", "source").withMaxAttempts(2));
        tasks.add(changeTask("impl", "implement", 2, "draft"));
        tasks.add(verifyTask("verify", "verify", List.of("impl"), "impl"));
        tasks.add(task("release", "release", "verify", "leaf1", "leaf2", "leaf3", "leaf4", "leaf5"));
        WorkflowRun run = harness.newRun(tasks.toArray(TaskSpec[]::new));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);
        return harness.log.events().stream()
                .map(e -> e.type() + " " + e.taskId() + " " + e.attempt() + " " + Stream.of(
                                e.text("artifact"), e.text("changeSet"), e.text("request"), e.text("gate"))
                        .filter(Objects::nonNull).findFirst().orElse("-"))
                .toList();
    }
}
