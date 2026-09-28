package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.task;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.FileChange;

class WorkflowEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void dependentWorkWaitsForAllPrerequisitesAndIndependentWorkRunsConcurrently() throws Exception {
        CyclicBarrier bothBranchesRunning = new CyclicBarrier(2);
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("root", ctx -> output("root", 1))
                .capability("branch", ctx -> {
                    // Deadlocks (and times out) unless both branches execute at the same time.
                    bothBranchesRunning.await(5, TimeUnit.SECONDS);
                    ctx.read("root", Map.class);
                    return output("branch/" + ctx.task().id(), ctx.task().id());
                })
                .capability("join", ctx -> {
                    ctx.read("branch/left", Map.class);
                    ctx.read("branch/right", Map.class);
                    return output("joined", true);
                });
        WorkflowRun run = harness.newRun(task("root", "root"), task("left", "branch", "root"),
                task("right", "branch", "root"), task("join", "join", "left", "right"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        List<ExecutionEvent> events = harness.log.events();
        long joinStarted = seqOf(events, EventType.TASK_STARTED, "join");
        assertThat(joinStarted).isGreaterThan(seqOf(events, EventType.TASK_SUCCEEDED, "left"))
                .isGreaterThan(seqOf(events, EventType.TASK_SUCCEEDED, "right"));
        assertThat(seqOf(events, EventType.TASK_STARTED, "left")).isGreaterThan(seqOf(events, EventType.TASK_SUCCEEDED, "root"));
        assertThat(run.task("join").inputs()).extracting(Object::toString).containsExactlyInAnyOrder("branch/left@v1", "branch/right@v1");
    }

    @Test
    void retriesAreBoundedAndExhaustionSafelyStopsTheRun() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("ok", ctx -> output("ok/" + ctx.task().id(), 1))
                .capability("flaky", ctx -> {
                    throw new TaskFailure(FailureKind.TRANSIENT, "tool unavailable");
                });
        WorkflowRun run = harness.newRun(task("start", "ok"), task("unstable", "flaky", "start").withMaxAttempts(3),
                task("afterUnstable", "ok", "unstable"), task("independent", "ok", "start"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertThat(harness.executions("unstable")).isEqualTo(3);
        assertThat(harness.count(EventType.RETRY_SCHEDULED)).isEqualTo(2);
        assertThat(run.task("unstable").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(run.task("afterUnstable").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(harness.executions("afterUnstable")).isZero();
        assertThat(run.verdict()).isEqualTo("NOT_READY");
    }

    @Test
    void fallbackCapabilityTakesOverAfterPrimaryIsExhausted() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("primary", List.of(), "secondary", ctx -> {
                    throw new TaskFailure(FailureKind.TRANSIENT, "primary provider timed out");
                })
                .capability("secondary", ctx -> output("result", "degraded"));
        WorkflowRun run = harness.newRun(task("work", "primary").withMaxAttempts(2).withFallback("secondary"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(run.task("work").onFallback()).isTrue();
        assertThat(harness.count(EventType.FALLBACK_ACTIVATED)).isEqualTo(1);
        assertThat(run.task("work").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.FAILED, AttemptRecord.Outcome.FAILED, AttemptRecord.Outcome.SUCCEEDED);
    }

    @Test
    void failingExitGateTriggersRetryWithFeedback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("quality", ctx -> ctx.output("draft", Map.class).map(m -> m.get("value")).filter("good"::equals).isPresent()
                        ? GateResult.pass("good") : GateResult.fail("draft is not good", List.of("value must be 'good'")))
                .capability("writer", List.of("quality"), null, ctx -> {
                    calls.incrementAndGet();
                    return output("draft", ctx.feedback().isEmpty() ? "bad" : "good");
                });
        WorkflowRun run = harness.newRun(task("write", "writer").withMaxAttempts(2));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);
        assertThat(calls).hasValue(2);
        assertThat(run.task("write").feedback()).singleElement().satisfies(f -> assertThat(f.details()).contains("value must be 'good'"));
        assertThat(run.artifacts().history("draft")).extracting(a -> a.status().name()).containsExactly("REJECTED", "CURRENT");
        assertThat(run.artifacts().current("draft").orElseThrow().content().path("value").asString()).isEqualTo("good");
    }

    @Test
    void policyDenialIsNeverAppliedAndIsRetriedWithTheViolationsAsFeedback() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> ctx.feedback().isEmpty()
                        ? change("edit applied migration", FileChange.edit("src/main/resources/db/migration/V1__init.sql", "id BIGINT", "id BIGINT, note TEXT"))
                        : change("add new code", FileChange.create("src/main/java/demo/Feature.java", "package demo;\nclass Feature {}\n")));
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/**"), List.of(), List.of(), List.of(), 2, null);
        WorkflowRun run = harness.newRun(implement);

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.workspace.read("src/main/resources/db/migration/V1__init.sql")).contains("CREATE TABLE t (id BIGINT);\n");
        assertThat(harness.workspace.exists("src/main/java/demo/Feature.java")).isTrue();
        assertThat(run.task("impl").feedback()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FailureKind.POLICY_DENIED);
            assertThat(f.details()).anyMatch(d -> d.contains("CC-01"));
        });
    }

    @Test
    void highImpactChangePausesForApprovalAndResumesAfterApproval() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("add migration",
                        FileChange.create("src/main/resources/db/migration/V2__add.sql", "ALTER TABLE t ADD COLUMN name VARCHAR(20);\n")));
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/resources/db/migration/*.sql"), List.of(), List.of(), List.of(), 1, null);
        WorkflowRun run = harness.newRun(implement);

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        assertThat(harness.workspace.exists("src/main/resources/db/migration/V2__add.sql")).isFalse();
        HumanRequest request = run.pendingHumanRequests().getFirst();
        assertThat(request.kind()).isEqualTo(HumanRequest.Kind.CHANGE_APPROVAL);
        assertThat(request.policyRules()).contains("CC-02");

        run.answer(request.id(), HumanResponse.approve("alice", "additive"), java.time.Instant.now());
        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);
        assertThat(harness.workspace.exists("src/main/resources/db/migration/V2__add.sql")).isTrue();
        assertThat(harness.executions("impl")).isEqualTo(1);
    }

    @Test
    void rejectedChangeIsNeverAppliedAndTheRunStopsSafely() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("add migration",
                        FileChange.create("src/main/resources/db/migration/V2__add.sql", "ALTER TABLE t ADD COLUMN name VARCHAR(20);\n")));
        harness.human = request -> Optional.of(HumanResponse.reject("bob", "not this quarter"));
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/**"), List.of(), List.of(), List.of(), 3, null);
        WorkflowRun run = harness.newRun(implement, task("after", "noop", "impl"));
        harness.capability("noop", ctx -> output("x", 1));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);
        assertThat(harness.workspace.exists("src/main/resources/db/migration/V2__add.sql")).isFalse();
        assertThat(run.task("impl").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(harness.executions("impl")).isEqualTo(1);
        assertThat(run.task("after").status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void safeStopCompensatesAppliedChangesAndRestoresTheBaseline() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create("src/main/java/demo/Feature.java", "package demo;\nclass Feature {}\n"),
                        FileChange.edit("src/main/java/demo/App.java", "class App {", "class App { // uses Feature")))
                .capability("broken", ctx -> {
                    throw new IllegalStateException("verifier crashed");
                });
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/java/**"), List.of(), List.of(), List.of(), 1, null);
        WorkflowRun run = harness.newRun(implement, task("verify", "broken", "impl"));
        String baseline = harness.workspace.baselineHash();

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertThat(harness.workspace.contentHash()).isEqualTo(baseline);
        assertThat(harness.workspace.exists("src/main/java/demo/Feature.java")).isFalse();
        assertThat(run.task("impl").status()).isEqualTo(TaskStatus.ROLLED_BACK);
        assertThat(run.appliedChanges()).isEmpty();
        assertThat(run.artifacts().current(ArtifactKeys.ABANDONED_CHANGES)).isPresent();
        assertThat(harness.log.events()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(EventType.WORKSPACE_RESTORED);
            assertThat(e.data().get("verified")).isEqualTo(true);
        });
    }

    @Test
    void codeDefectFoundByVerifierSendsOnlyTheVerifiedWorkBackForRework() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("attempt " + ctx.attempt(), FileChange.create("src/main/java/demo/Feature.java",
                        ctx.feedback().isEmpty() ? "class Feature { int answer() { return 41; } }\n" : "class Feature { int answer() { return 42; } }\n")))
                .gate("tests-pass", ctx -> ctx.workspace().read("src/main/java/demo/Feature.java").orElse("").contains("42")
                        ? GateResult.pass("all tests passed") : GateResult.defect("FeatureTest failed", List.of("expected 42 but was 41"), List.of()))
                .capability("verify", List.of("tests-pass"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("verification", "ran");
                })
                .capability("docs", ctx -> output("docs", "unrelated"));
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/java/**"), List.of(), List.of(), List.of(), 2, null);
        TaskSpec verify = new TaskSpec("verify", "verify", Stage.TESTING, "verify", List.of("impl"), "", List.of(),
                List.of(), List.of("impl"), List.of(), List.of(), 1, null);
        WorkflowRun run = harness.newRun(implement, verify, task("docs", "docs"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.workspace.read("src/main/java/demo/Feature.java")).hasValueSatisfying(s -> assertThat(s).contains("42"));
        assertThat(harness.executions("impl")).isEqualTo(2);
        assertThat(harness.executions("verify")).isEqualTo(2);
        assertThat(harness.executions("docs")).isEqualTo(1);
        assertThat(harness.count(EventType.REWORK_REQUESTED)).isEqualTo(1);
        assertThat(harness.count(EventType.CHANGESET_ROLLED_BACK)).isEqualTo(1);
        assertThat(run.artifacts().history(ArtifactKeys.changesOf("impl"))).extracting(a -> a.status().name())
                .containsExactly("RETRACTED", "CURRENT");
    }

    @Test
    void reworkIsBoundedByTheTargetsAttemptBudget() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("always wrong", FileChange.create("src/main/java/demo/Feature.java", "class Feature {}\n")))
                .gate("tests-pass", ctx -> GateResult.defect("still failing", List.of(), List.of()))
                .capability("verify", List.of("tests-pass"), null, ctx -> output("verification", "ran"));
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/java/**"), List.of(), List.of(), List.of(), 2, null);
        TaskSpec verify = new TaskSpec("verify", "verify", Stage.TESTING, "verify", List.of("impl"), "", List.of(),
                List.of(), List.of("impl"), List.of(), List.of(), 1, null);
        WorkflowRun run = harness.newRun(implement, verify);
        String baseline = harness.workspace.baselineHash();

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);
        assertThat(harness.executions("impl")).isEqualTo(2);
        assertThat(run.task("verify").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(harness.workspace.contentHash()).isEqualTo(baseline);
    }

    @Test
    void upstreamChangeInvalidatesOnlyConsumersOfTheChangedArtifact() throws Exception {
        AtomicInteger designCalls = new AtomicInteger();
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("design", ctx -> {
                    int call = designCalls.incrementAndGet();
                    return TaskResult.of("design", Map.of(
                            "design/contract", OutputArtifact.of("contract", Map.of("status", call == 1 ? 410 : 404)),
                            "design/data-model", OutputArtifact.of("data-model", Map.of("columns", List.of("expires_at")))));
                })
                .changeCapability("implement", ctx -> {
                    Map<?, ?> contract = ctx.read("design/contract", Map.class);
                    return change("handler", FileChange.create("src/main/java/demo/Handler.java", "// status " + contract.get("status") + "\n"));
                })
                .changeCapability("migrate", ctx -> {
                    ctx.read("design/data-model", Map.class);
                    return change("migration", FileChange.create("src/main/java/demo/Schema.java", "// expires_at\n"));
                })
                .capability("release", List.of("release-approval"), null, ctx -> {
                    ctx.readAll(ArtifactKeys.CHANGES_PREFIX);
                    return output("release", "assessed");
                })
                // The production sign-off gate: it asks on every attempt, so both answers below are really used.
                .standardGate("release-approval");
        List<HumanRequest> asked = new CopyOnWriteArrayList<>();
        harness.human = request -> {
            asked.add(request);
            return Optional.of(asked.size() == 1
                    ? HumanResponse.requestChanges("carol", "expired links must return 404", "design")
                    : HumanResponse.approve("carol", "ok"));
        };
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of("design"), "", List.of(),
                List.of("src/main/java/**"), List.of(), List.of(), List.of(), 1, null);
        TaskSpec migrate = new TaskSpec("migrate", "migrate", Stage.IMPLEMENTATION, "migrate", List.of("design"), "", List.of(),
                List.of("src/main/java/**"), List.of(), List.of(), List.of(), 1, null);
        WorkflowRun run = harness.newRun(task("design", "design"), implement, migrate, task("release", "release", "impl", "migrate"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.executions("design")).isEqualTo(2);
        assertThat(harness.executions("impl")).isEqualTo(2);
        assertThat(harness.executions("migrate")).as("unrelated completed work is preserved").isEqualTo(1);
        assertThat(harness.workspace.read("src/main/java/demo/Handler.java")).hasValue("// status 404\n");
        assertThat(run.artifacts().history("design/data-model")).hasSize(1);
        assertThat(run.artifacts().history("design/contract")).hasSize(2);
        assertThat(harness.log.events()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(EventType.ARTIFACT_UNCHANGED);
            assertThat(e.text("artifact")).isEqualTo("design/data-model@v1");
        });

        // Both human decisions were asked for and applied, in order.
        assertThat(asked).hasSize(2).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo(HumanRequest.Kind.GATE_APPROVAL);
            assertThat(r.taskId()).isEqualTo("release");
            assertThat(r.gate()).isEqualTo("release-approval");
        });
        assertThat(run.humanRequests()).extracting(HumanRequest::status).containsOnly(HumanRequest.Status.APPLIED);
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.HUMAN_INPUT_RECEIVED)
                .extracting(e -> e.text("decision")).containsExactly("REQUEST_CHANGES", "APPROVE");
        assertThat(run.artifacts().current("approval/" + asked.get(0).id())).isEmpty();
        assertThat(run.artifacts().current("approval/" + asked.get(1).id())).isPresent();
        // Release ran exactly twice and never while design (its ancestor) was being redone.
        assertThat(harness.executions("release")).isEqualTo(2);
        assertThat(harness.startsWhileRedoing("release", "design")).as("release starts while design was re-running").isEmpty();
        assertThat(harness.lastSeq(EventType.TASK_STARTED, "release")).isGreaterThan(harness.lastSeq(EventType.TASK_SUCCEEDED, "impl"));
    }

    @Test
    void clarificationPausesTheRunAndAnswersFeedTheNextAttempt() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("unambiguous", ctx -> ctx.output("spec", Map.class).map(m -> m.get("value")).filter("clear"::equals).isPresent()
                        ? GateResult.pass("clear")
                        : GateResult.clarification("What should happen to expired links?", List.of(),
                        List.of(new HumanRequest.Question("Q1", "Status for expired links?", "affects clients",
                                List.of(new HumanRequest.Option("410", "410 Gone", ""), new HumanRequest.Option("404", "404", "")), false))))
                .capability("analyse", List.of("unambiguous"), null, ctx -> output("spec",
                        ctx.find(ArtifactKeys.CLARIFICATIONS, Map.class).isPresent() ? "clear" : "vague"))
                .capability("design", ctx -> {
                    ctx.read("spec", Map.class);
                    return output("design", "done");
                });
        WorkflowRun run = harness.newRun(task("analyse", "analyse").withMaxAttempts(2), task("design", "design", "analyse"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        assertThat(harness.executions("design")).isZero();
        HumanRequest question = run.pendingHumanRequests().getFirst();
        assertThat(question.kind()).isEqualTo(HumanRequest.Kind.CLARIFICATION);

        run.answer(question.id(), HumanResponse.answer("dana", Map.of("Q1", "410")), java.time.Instant.now());
        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.task("analyse").inputs()).extracting(Object::toString).contains(ArtifactKeys.CLARIFICATIONS + "@v1");
        assertThat(run.artifacts().current(ArtifactKeys.CLARIFICATIONS).orElseThrow().producer()).isEqualTo("human:dana");
    }

    @Test
    void attemptsOrphanedByAnInterruptedProcessRunAgainOnResume() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir).capability("work", ctx -> output("result", 1));
        WorkflowRun run = harness.newRun(task("work", "work"));
        // Simulates a crash: state was persisted while the attempt was running, then the process died.
        run.status(RunStatus.RUNNING, null);
        run.task("work").startAttempt();

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.task("work").history()).extracting(AttemptRecord::outcome)
                .containsExactly(AttemptRecord.Outcome.DISCARDED, AttemptRecord.Outcome.SUCCEEDED);
        assertThat(harness.eventTypes("work")).startsWith(EventType.ATTEMPT_DISCARDED);
    }

    @Test
    void decisionsMustFitTheKindOfCheckpoint() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .gate("unambiguous", ctx -> GateResult.clarification("?", List.of(),
                        List.of(new HumanRequest.Question("Q1", "which?", "", List.of(), true))))
                .capability("analyse", List.of("unambiguous"), null, ctx -> output("spec", "vague"));
        WorkflowRun run = harness.newRun(task("analyse", "analyse"));
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        String request = run.pendingHumanRequests().getFirst().id();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        run.answer(request, HumanResponse.approve("dana", "sure"), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CLARIFICATION");
        assertThat(run.pendingHumanRequests()).hasSize(1);
    }

    @Test
    void humansCanSafelyStopAPausedRunAndEveryChangeIsCompensated() throws Exception {
        EngineHarness harness = new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create("src/main/java/demo/Feature.java", "class Feature {}\n")))
                .changeCapability("migrate", ctx -> change("migration",
                        FileChange.create("src/main/resources/db/migration/V2__add.sql", "ALTER TABLE t ADD COLUMN c INT;\n")));
        TaskSpec implement = new TaskSpec("impl", "impl", Stage.IMPLEMENTATION, "implement", List.of(), "", List.of(),
                List.of("src/main/**"), List.of(), List.of(), List.of(), 1, null);
        TaskSpec migrate = new TaskSpec("migrate", "migrate", Stage.IMPLEMENTATION, "migrate", List.of("impl"), "", List.of(),
                List.of("src/main/**"), List.of(), List.of(), List.of(), 1, null);
        WorkflowRun run = harness.newRun(implement, migrate);
        String baseline = harness.workspace.baselineHash();
        assertThat(harness.execute(run)).isEqualTo(RunStatus.AWAITING_HUMAN);
        assertThat(harness.workspace.exists("src/main/java/demo/Feature.java")).isTrue();

        assertThat(harness.engine().stop(run, harness.workspace, harness.log, r -> { }, "cancelled by ops: wrong quarter"))
                .isEqualTo(RunStatus.HALTED);

        assertThat(harness.workspace.contentHash()).isEqualTo(baseline);
        assertThat(run.pendingHumanRequests()).isEmpty();
        assertThat(run.humanRequests()).extracting(HumanRequest::status).containsExactly(HumanRequest.Status.CANCELLED);
        assertThat(run.task("impl").status()).isEqualTo(TaskStatus.ROLLED_BACK);
        assertThat(run.task("migrate").status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(run.statusReason()).contains("wrong quarter");
    }

    private static long seqOf(List<ExecutionEvent> events, EventType type, String taskId) {
        return events.stream().filter(e -> e.type() == type && taskId.equals(e.taskId())).mapToLong(ExecutionEvent::seq).findFirst().orElseThrow();
    }
}
