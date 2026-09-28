package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.await;
import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.refs;
import static com.example.sdlc.engine.EngineHarness.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.PlanProposal;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.FileChange;

/**
 * Plan revisions produced by a planner capability (validated by the real {@link PlanValidator}) and the
 * re-planning budget: what is preserved, what is redone, what is compensated, and when it stops.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PlanRevisionTest {

    @TempDir
    Path tempDir;

    private static TaskSpec planned(String id, String capability, String goal, String... dependsOn) {
        return new TaskSpec(id, id, null, capability, List.of(dependsOn), goal, List.of(), List.of(), List.of(),
                List.of(), List.of(), 0, null);
    }

    private static TaskSpec plannedChange(String id, String... dependsOn) {
        return new TaskSpec(id, id, null, "implement", List.of(dependsOn), "", List.of(), List.of("src/main/java/**"),
                List.of(), List.of(), List.of(), 0, null);
    }

    private static TaskSpec plannedVerify(String id, List<String> verifies, String... dependsOn) {
        return new TaskSpec(id, id, null, "check", List.of(dependsOn), "", List.of(), List.of(), verifies,
                List.of(), List.of(), 0, null);
    }

    private static TaskResult proposal(String rationale, TaskSpec... tasks) {
        return TaskResult.of("proposed " + tasks.length + " tasks", ArtifactKeys.PLAN_PROPOSAL,
                OutputArtifact.of("plan", new PlanProposal(rationale, List.of(tasks))));
    }

    private static TaskResult firstPlan() {
        return proposal("two changes",
                plannedChange("implA"),
                plannedChange("implB"),
                planned("docs", "write", "describe A and B"),
                plannedVerify("verify", List.of("implA", "implB"), "implA", "implB"),
                planned("security", "secure", "", "implA", "implB"),
                planned("api", "compat", "", "implA", "implB"),
                planned("release", "ship", "", "verify", "security", "api", "docs"));
    }

    private static TaskResult secondPlan() {
        return proposal("B is out of scope; notes first",
                plannedChange("implA"),
                planned("notes", "write", "release notes"),
                planned("docs", "write", "describe A only", "notes"),
                plannedVerify("verify", List.of("implA"), "implA"),
                planned("security", "secure", "", "implA"),
                planned("api", "compat", "", "implA"),
                planned("release", "ship", "", "verify", "security", "api", "docs"));
    }

    /** Governance-complete catalogue: change, verification, security and compatibility review, and a release with human sign-off. */
    private EngineHarness plannedHarness(TaskHandler planner) throws Exception {
        return new EngineHarness(tempDir)
                .plannerCapability("plan", planner)
                .changeCapability("implement", ctx -> change(ctx.task().id(),
                        FileChange.create("src/main/java/demo/" + ctx.task().id() + ".java", "class " + ctx.task().id() + " {}\n")))
                .capability("write", ctx -> output("doc/" + ctx.task().id(), ctx.task().goal()))
                .roleCapability("check", Capability.Role.VERIFICATION, List.of(),
                        ctx -> output("verification", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .roleCapability("secure", Capability.Role.SECURITY_REVIEW, List.of(),
                        ctx -> output("security", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .roleCapability("compat", Capability.Role.COMPATIBILITY_REVIEW, List.of(),
                        ctx -> output("compatibility", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .roleCapability("ship", Capability.Role.RELEASE, List.of("signoff"), ctx -> output("release", refs(ctx.readAll(""))))
                .gate("signoff", ctx -> GateResult.approval("ship " + ctx.task().id() + "?", List.of()));
    }

    private static HumanResponse changesToPlannerThenApprove(AtomicInteger asked) {
        return asked.incrementAndGet() == 1
                ? HumanResponse.requestChanges("pat", "drop B, write release notes first", "planner")
                : HumanResponse.approve("pat", "ok");
    }

    @Test
    void revisedPlanPreservesUnchangedWorkRerunsChangedTasksAndCompensatesRemovedOnes() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        EngineHarness harness = plannedHarness(ctx -> ctx.feedback().isEmpty() ? firstPlan() : secondPlan());
        harness.human = request -> Optional.of(changesToPlannerThenApprove(asked));
        WorkflowRun run = harness.newRun(task("planner", "plan"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(run.plan().version()).isEqualTo(3);
        assertThat(run.planHistory()).hasSize(3);
        List<ExecutionEvent> revisions = harness.log.events().stream().filter(e -> e.type() == EventType.PLAN_REVISED).toList();
        assertThat(revisions).hasSize(2);
        assertThat(revisions.getLast().data())
                .containsEntry("version", 3)
                .containsEntry("added", List.of("notes"))
                .containsEntry("changed", List.of("docs", "verify", "security", "api"))
                .containsEntry("removed", List.of("implB"))
                .containsEntry("preserved", List.of("planner", "implA", "release"));

        // Removed: compensated, retracted, cancelled - and never run again.
        assertThat(run.task("implB").status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(harness.events(EventType.CHANGESET_ROLLED_BACK, "implB")).singleElement()
                .satisfies(e -> assertThat(e.text("cause")).isEqualTo("invalidation"));
        assertThat(harness.workspace.exists("src/main/java/demo/implB.java")).isFalse();
        assertThat(run.artifacts().current(ArtifactKeys.changesOf("implB"))).isEmpty();
        assertThat(harness.executions("implB")).isEqualTo(1);
        // Preserved: untouched.
        assertThat(harness.executions("implA")).isEqualTo(1);
        assertThat(harness.workspace.exists("src/main/java/demo/implA.java")).isTrue();
        assertThat(run.appliedChanges()).extracting(AppliedChangeSet::taskId).containsExactly("implA");
        // Changed and added: (re-)run against the new plan.
        assertThat(harness.executions("docs")).isEqualTo(2);
        assertThat(run.artifacts().current("doc/docs").orElseThrow().content().path("value").asString()).isEqualTo("describe A only");
        assertThat(harness.executions("notes")).isEqualTo(1);
        assertThat(harness.executions("verify")).isEqualTo(2);
        assertThat(run.artifacts().current("verification").orElseThrow().content().path("value").get(0).asString())
                .isEqualTo("changes/implA@v1");
        assertThat(run.artifacts().current("verification").orElseThrow().content().path("value")).hasSize(1);
        assertThat(harness.executions("security")).isEqualTo(2);
        assertThat(harness.executions("release")).isEqualTo(2);
        assertThat(harness.executions("planner")).isEqualTo(2);
        assertThat(run.replans()).isEqualTo(2);
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.HUMAN_INPUT_RECEIVED)
                .extracting(e -> e.text("decision")).containsExactly("REQUEST_CHANGES", "APPROVE");
    }

    @Test
    void identicalReproposalKeepsThePlanVersionAndIsNotCountedAsARePlan() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        EngineHarness harness = plannedHarness(ctx -> firstPlan());
        harness.human = request -> Optional.of(changesToPlannerThenApprove(asked));
        WorkflowRun run = harness.newRun(task("planner", "plan"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.executions("planner")).isEqualTo(2);
        assertThat(harness.count(EventType.PLAN_REVISED)).isEqualTo(1);
        assertThat(run.planHistory()).hasSize(2);
        assertThat(run.plan().version()).isEqualTo(2);
        assertThat(run.replans()).as("only the change request itself counts").isEqualTo(1);
        assertThat(harness.events(EventType.ARTIFACT_UNCHANGED, "planner"))
                .anySatisfy(e -> assertThat(e.text("artifact")).isEqualTo(ArtifactKeys.PLAN_PROPOSAL + "@v1"));
        assertThat(run.artifacts().history(ArtifactKeys.PLAN_PROPOSAL)).hasSize(1);
        for (String unaffected : List.of("implA", "implB", "docs", "verify", "security")) {
            assertThat(harness.executions(unaffected)).as(unaffected).isEqualTo(1);
        }
        assertThat(harness.executions("release")).isEqualTo(2);
    }

    @Test
    void attemptWhoseSpecIsRevisedWhileItRunsIsDiscardedAndRerunsWithTheNewSpec() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        List<String> goalsSeen = new CopyOnWriteArrayList<>();
        EngineHarness harness = new EngineHarness(tempDir);
        WorkflowRun run = harness.newRun(task("planner", "plan"));
        CountDownLatch revised = harness.signalOn(e -> e.type() == EventType.PLAN_REVISED && Integer.valueOf(3).equals(e.data().get("version")));
        harness.plannerCapability("plan", ctx -> proposal("slow work",
                        planned("review", "reviewing", ""),
                        planned("slow", "slowwork", ctx.feedback().isEmpty() ? "first" : "second"),
                        planned("release", "ship", "", "review", "slow")))
                .gate("ask", ctx -> GateResult.approval("review ok?", List.of()))
                .capability("reviewing", List.of("ask"), null, ctx -> output("review", "done"))
                .capability("slowwork", ctx -> {
                    goalsSeen.add(ctx.task().goal());
                    if (ctx.invocation() == 1) {
                        await(revised, "the planner to revise this task");
                    }
                    return output("slow", ctx.task().goal());
                })
                .roleCapability("ship", Capability.Role.RELEASE, List.of(), ctx -> output("release", refs(ctx.readAll(""))));
        harness.human = request -> Optional.of(changesToPlannerThenApprove(asked));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(goalsSeen).containsExactly("first", "second");
        assertThat(run.task("slow").history()).extracting(AttemptRecord::outcome, AttemptRecord::message).containsExactly(
                tuple(AttemptRecord.Outcome.DISCARDED, "superseded while running"),
                tuple(AttemptRecord.Outcome.SUCCEEDED, "produced slow"));
        assertThat(harness.events(EventType.ATTEMPT_DISCARDED, "slow")).singleElement().satisfies(e -> {
            assertThat(e.message()).contains("invalidated while this attempt was running");
            assertThat(e.seq()).isGreaterThan(harness.log.events().stream()
                    .filter(p -> p.type() == EventType.PLAN_REVISED).mapToLong(ExecutionEvent::seq).max().orElseThrow());
        });
        assertThat(run.artifacts().current("slow").orElseThrow().content().path("value").asString()).isEqualTo("second");
        assertThat(run.artifacts().history("slow")).hasSize(1);
        assertThat(harness.executions("slow")).isEqualTo(2);
        assertThat(run.plan().task("slow").goal()).isEqualTo("second");
    }

    @Test
    void reviewerWhoKeepsRequestingUpstreamChangesExhaustsTheRePlanBudget() throws Exception {
        List<HumanRequest> asked = new CopyOnWriteArrayList<>();
        EngineHarness harness = new EngineHarness(tempDir)
                .capability("design", ctx -> output("design", "revision " + ctx.invocation()))
                .gate("signoff", ctx -> GateResult.approval("sign off", List.of()))
                .capability("release", List.of("signoff"), null, ctx -> output("release", ctx.read("design", Map.class).get("value")));
        harness.settings = new WorkflowEngine.Settings(4, 2, 2);
        harness.human = request -> {
            asked.add(request);
            // Bounded here too, so a missing budget check ends in a (different) halt instead of looping forever.
            return Optional.of(asked.size() <= 5 ? HumanResponse.requestChanges("quinn", "not yet", "design")
                    : HumanResponse.reject("quinn", "giving up"));
        };
        WorkflowRun run = harness.newRun(task("design", "design"), task("release", "release", "design"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertThat(run.statusReason()).contains("re-plan budget of 2 exhausted");
        assertThat(asked).hasSize(3);
        assertThat(run.replans()).isEqualTo(3);
        assertThat(harness.executions("design")).isEqualTo(3);
        assertThat(harness.executions("release")).isEqualTo(3);
        assertThat(run.verdict()).isEqualTo("NOT_READY");
    }
}
