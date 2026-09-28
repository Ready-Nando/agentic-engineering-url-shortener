package com.example.sdlc.engine;

import static com.example.sdlc.engine.EngineHarness.change;
import static com.example.sdlc.engine.EngineHarness.changeTask;
import static com.example.sdlc.engine.EngineHarness.output;
import static com.example.sdlc.engine.EngineHarness.refs;
import static com.example.sdlc.engine.EngineHarness.verifyTask;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.example.sdlc.workspace.FileChange;

/** Rework follows the verifier's evidence: only implicated work inside its declared coverage is sent back. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReworkCoverageTest {

    private static final String FEATURE = "src/main/java/demo/Feature.java";
    private static final String GUIDE = "src/main/resources/docs/guide.md";

    @TempDir
    Path tempDir;

    private EngineHarness harness(List<String> suspects) throws Exception {
        return new EngineHarness(tempDir)
                .changeCapability("implement", ctx -> change("feature", FileChange.create(FEATURE, "class Feature {}\n")))
                .changeCapability("document", ctx -> change("guide", FileChange.create(GUIDE,
                        ctx.feedback().isEmpty() ? "Call Feature.old()\n" : "Call Feature.run()\n")))
                .gate("tests-pass", ctx -> ctx.workspace().read(GUIDE).orElse("").contains("old()")
                        ? GateResult.defect("GuideTest failed", List.of("guide names a method that does not exist"), suspects)
                        : GateResult.pass("passed"))
                .capability("verify", List.of("tests-pass"), null, ctx -> output("verification", refs(ctx.readAll(ArtifactKeys.CHANGES_PREFIX))));
    }

    @Test
    void suspectsInsideCoverageReworkOnlyTheSuspects() throws Exception {
        EngineHarness harness = harness(List.of("docs"));
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2), changeTask("docs", "document", 2),
                verifyTask("verify", "verify", List.of("impl", "docs"), "impl", "docs"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.COMPLETED);

        assertThat(harness.events(EventType.REWORK_REQUESTED, "verify")).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("possible", true).containsEntry("targets", List.of("docs"));
        });
        assertThat(harness.log.events()).filteredOn(e -> e.type() == EventType.CHANGESET_ROLLED_BACK)
                .extracting(ExecutionEvent::taskId).containsExactly("docs");
        assertThat(harness.executions("impl")).isEqualTo(1);
        assertThat(harness.executions("docs")).isEqualTo(2);
        assertThat(harness.executions("verify")).isEqualTo(2);
        assertThat(run.task("impl").history()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(AttemptRecord.Outcome.SUCCEEDED));
        assertThat(harness.workspace.read(GUIDE)).hasValue("Call Feature.run()\n");
        assertThat(run.appliedChanges()).extracting(AppliedChangeSet::taskId).containsExactlyInAnyOrder("impl", "docs");
        assertThat(harness.events(EventType.GATE_PASSED, "verify")).anySatisfy(e -> assertThat(e.text("gate")).isEqualTo("tests-pass"));
    }

    @Test
    void suspectsOutsideCoverageFailTheVerifierInsteadOfReworkingInnocentWork() throws Exception {
        EngineHarness harness = harness(List.of("docs"));
        // The verifier only claims to cover impl, yet its evidence implicates docs.
        WorkflowRun run = harness.newRun(changeTask("impl", "implement", 2), changeTask("docs", "document", 2),
                verifyTask("verify", "verify", List.of("impl"), "impl", "docs"));

        assertThat(harness.execute(run)).isEqualTo(RunStatus.HALTED);

        assertThat(run.task("verify").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(harness.events(EventType.TASK_FAILED, "verify")).singleElement().satisfies(e -> {
            assertThat(e.text("kind")).isEqualTo("CODE_DEFECT");
            assertThat(e.message()).contains("[docs]").contains("declared coverage [impl]");
        });
        assertThat(harness.events(EventType.REWORK_REQUESTED, "verify")).noneSatisfy(
                e -> assertThat(e.data()).containsEntry("possible", true));
        assertThat(harness.count(EventType.TASK_INVALIDATED)).isZero();
        assertThat(harness.executions("impl")).isEqualTo(1);
        assertThat(harness.executions("docs")).isEqualTo(1);
        assertThat(harness.executions("verify")).isEqualTo(1);
        assertThat(run.statusReason()).contains("all changes compensated").doesNotContain("manual intervention");
        assertThat(run.appliedChanges()).isEmpty();
        assertThat(harness.workspace.contentHash()).isEqualTo(harness.workspace.baselineHash());
    }
}
