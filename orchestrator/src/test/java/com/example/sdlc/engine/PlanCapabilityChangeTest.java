package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.refs;
import static com.example.sdlc.engine.EngineHarness.task;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.PlanProposal;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.FileChange;

/**
 * A revised plan that keeps a task id but gives it a different capability: whether the old work is in the
 * workspace follows from the capability it ran with, not from the one it will run with next.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PlanCapabilityChangeTest {

    private static final String IMPL_B = "src/main/java/demo/implB.java";

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

    /** Every change verified, security and compatibility reviewed, one release closing the plan. */
    private static TaskResult twoChanges() {
        return proposal("two changes",
                plannedChange("implA"),
                plannedChange("implB"),
                plannedVerify("verify", List.of("implA", "implB"), "implA", "implB"),
                planned("security", "secure", "", "implA", "implB"),
                planned("compat", "compat", "", "implA", "implB"),
                planned("release", "ship", "", "verify", "security", "compat"));
    }

    /** implB keeps its id but only documents now: its earlier code change must leave the workspace. */
    private static TaskResult implBBecomesPure() {
        return proposal("B only needs notes",
                plannedChange("implA"),
                planned("implB", "write", "notes instead of code"),
                plannedVerify("verify", List.of("implA"), "implA"),
                planned("security", "secure", "", "implA"),
                planned("compat", "compat", "", "implA"),
                planned("release", "ship", "", "verify", "security", "compat", "implB"));
    }

    @Test
    void taskIdReusedForAPureCapabilityHasItsAppliedChangeSetRolledBack() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        EngineHarness harness = new EngineHarness(tempDir)
                .plannerCapability("plan", ctx -> ctx.feedback().isEmpty() ? twoChanges() : implBBecomesPure())
                .changeCapability("implement", ctx -> change(ctx.task().id(),
                        FileChange.create("src/main/java/demo/" + ctx.task().id() + ".java", "class " + ctx.task().id() + " {}\n")))
                .capability("write", ctx -> output("doc/" + ctx.task().id(), ctx.task().goal()))
                .roleCapability("check", Capability.Role.VERIFICATION, List.of(),
                        ctx -> output("verification", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .roleCapability("secure", Capability.Role.SECURITY_REVIEW, List.of(),
                        ctx -> output("security", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .roleCapability("compat", Capability.Role.COMPATIBILITY_REVIEW, List.of(),
                        ctx -> output("compatibility", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))))
                .roleCapability("ship", Capability.Role.RELEASE, List.of("signoff"), ctx -> output("release", "assessed"))
                .gate("signoff", ctx -> GateResult.approval("ship " + ctx.task().id() + "?", List.of()));
        harness.human = request -> Optional.of(asked.incrementAndGet() == 1
                ? HumanResponse.requestChanges("pat", "B needs notes, not code", "planner")
                : HumanResponse.approve("pat", "ok"));
        WorkflowRun run = harness.newRun(task("planner", "plan"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.PLAN_REVISED).last()
                .satisfies(e -> assertThat(e.data().get("changed")).asList().contains("implB"));
        assertThat(run.plan().task("implB").capability()).isEqualTo("write");
        assertThat(harness.events(EventType.CHANGESET_ROLLED_BACK, "implB")).singleElement()
                .satisfies(e -> assertThat(e.text("cause")).isEqualTo("invalidation"));
        assertThat(harness.workspace.exists(IMPL_B)).as("back at its pre-image (absent)").isFalse();
        assertThat(run.appliedChanges()).extracting(AppliedChangeSet::taskId).containsExactly("implA");
        assertThat(run.artifacts().current(ArtifactKeys.changesOf("implB"))).isEmpty();
        assertThat(run.artifacts().current("doc/implB").orElseThrow().content().path("value").asString())
                .isEqualTo("notes instead of code");
        assertThat(harness.workspace.changedPaths()).containsExactly("src/main/java/demo/implA.java");
        assertThat(run.artifacts().current("verification").orElseThrow().content().path("value").get(0).asString())
                .isEqualTo("changes/implA@v1");
        assertThat(harness.executions("implA")).isEqualTo(1);
    }
}
