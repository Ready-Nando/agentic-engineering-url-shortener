package com.example.sdlc.gates;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.capability.VerificationReport;
import com.example.sdlc.codebase.ArchitectureRules;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.engine.AttemptResult;
import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.GateContext;
import com.example.sdlc.engine.GateResult;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskState;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.plan.Stage;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.reasoning.DesignProposal;
import com.example.sdlc.reasoning.Impact;
import com.example.sdlc.reasoning.RequirementSpec;
import com.example.sdlc.verify.FailedTest;
import com.example.sdlc.workspace.Workspace;

class StandardGatesTest {

    @TempDir
    Path temp;

    private StandardGates gates;
    private Workspace workspace;
    private WorkflowRun run;
    private final TaskSpec task = TaskSpec.of("task", Stage.REQUIREMENTS, "analyze-requirement", List.of());

    @BeforeEach
    void setUp() throws Exception {
        Path module = temp.resolve("module");
        Files.createDirectories(module.resolve("src/test/java/demo"));
        Files.writeString(module.resolve("src/test/java/demo/FeatureTest.java"), "class FeatureTest { /* AC-1 */ }\n");
        workspace = Workspace.create(module, Map.of(), temp.resolve("run"));
        run = new WorkflowRun("r", "s", "t", "req", Instant.now(), WorkflowPlan.of(1, List.of(task), "", ""), Map.of());
        gates = new StandardGates(new CodebaseIndexer(), new ArchitectureRules());
    }

    private GateContext withOutputs(Map<String, OutputArtifact> outputs) {
        return new GateContext(run, task, new TaskState("task"),
                new AttemptResult("task", 1, 1, outputs, null, List.of(), "", null, Instant.now(), "test"), workspace);
    }

    private static RequirementSpec spec(List<RequirementSpec.AcceptanceCriterion> criteria, List<RequirementSpec.Assumption> assumptions,
                                        List<RequirementSpec.OpenQuestion> questions) {
        return new RequirementSpec("Title", "Summary", "ENHANCEMENT", criteria, List.of(), assumptions, List.of(), questions, List.of());
    }

    private static final RequirementSpec.AcceptanceCriterion MEASURABLE =
            new RequirementSpec.AcceptanceCriterion("AC-1", "Expired links return 410 within 1 second of expiry", "API test");

    @Test
    void wellFormedRequirementPasses() {
        GateResult result = gates.requirementQuality(withOutputs(Map.of(ArtifactKeys.REQUIREMENT_SPEC,
                OutputArtifact.of("spec", spec(List.of(MEASURABLE), List.of(), List.of())))));

        assertThat(result.verdict()).isEqualTo(GateResult.Verdict.PASS);
    }

    @Test
    void stopsForBlockingQuestionsOverconfidentAssumptionsAndUnmeasurableCriteria() {
        RequirementSpec ambiguous = spec(
                List.of(MEASURABLE, new RequirementSpec.AcceptanceCriterion("AC-2", "Expired links are handled appropriately", "tbd")),
                List.of(new RequirementSpec.Assumption("A-1", "Existing links expire retroactively", Impact.HIGH, false),
                        new RequirementSpec.Assumption("A-2", "Timestamps are UTC", Impact.LOW, false)),
                List.of(new RequirementSpec.OpenQuestion("Q-1", "Which links expire?", "scope", List.of(), true),
                        new RequirementSpec.OpenQuestion("Q-2", "Nice-to-have?", "", List.of(), false)));

        GateResult result = gates.requirementQuality(withOutputs(Map.of(ArtifactKeys.REQUIREMENT_SPEC, OutputArtifact.of("spec", ambiguous))));

        assertThat(result.verdict()).isEqualTo(GateResult.Verdict.NEEDS_HUMAN);
        assertThat(result.humanNeed().kind()).isEqualTo(HumanRequest.Kind.CLARIFICATION);
        assertThat(result.humanNeed().questions()).extracting(HumanRequest.Question::id).containsExactly("Q-1", "A-1", "AC-2");
        assertThat(result.humanNeed().questions().get(1).options()).extracting(HumanRequest.Option::id).containsExactly("confirm", "reject");
    }

    @Test
    void specificationWithoutCriteriaIsSentBackForRetry() {
        GateResult result = gates.requirementQuality(withOutputs(Map.of(ArtifactKeys.REQUIREMENT_SPEC,
                OutputArtifact.of("spec", spec(List.of(), List.of(), List.of())))));

        assertThat(result.verdict()).isEqualTo(GateResult.Verdict.FAIL);
        assertThat(result.failureKind()).isEqualTo(FailureKind.GATE_FAILED);
    }

    @Test
    void designWithRawPersonalDataFailsAndHighImpactDecisionsNeedSignOff() {
        DesignProposal.DecisionRecord decision = new DesignProposal.DecisionRecord("D-1", "Auto takedown", "ctx",
                List.of("manual", "automatic"), "automatic", "speed", Impact.HIGH);
        Map<String, OutputArtifact> outputs = Map.of(
                ArtifactKeys.DECISION_PREFIX + "D-1", OutputArtifact.of("decision", decision),
                ArtifactKeys.API_CONTRACT, OutputArtifact.of("api", Map.of("operations", List.of(
                        Map.of("method", "POST", "path", "/api/v1/things", "responses", List.of(202))))),
                ArtifactKeys.DATA_MODEL, OutputArtifact.of("model", Map.of("tables", List.of(Map.of("table", "report", "change", "CREATE",
                        "columns", List.of(Map.of("name", "reporter_ip", "type", "VARCHAR(45)", "nullable", false, "personalData", "RAW")))))));

        GateResult quality = gates.designQuality(withOutputs(outputs));
        GateResult approval = gates.designApproval(withOutputs(outputs));

        assertThat(quality.verdict()).isEqualTo(GateResult.Verdict.FAIL);
        assertThat(quality.details()).anyMatch(d -> d.contains("raw personal data")).anyMatch(d -> d.contains("no 4xx"));
        assertThat(approval.verdict()).isEqualTo(GateResult.Verdict.NEEDS_HUMAN);
        assertThat(approval.details()).anyMatch(d -> d.contains("HIGH impact decision D-1"));
    }

    @Test
    void impactSeedsMustExistInTheCodebase() {
        GateResult result = gates.impactGrounded(withOutputs(Map.of(ArtifactKeys.IMPACT_ANALYSIS, OutputArtifact.of("impact", Map.of(
                "seeds", List.of("LinkService", "QuantumCache"),
                "analysis", Map.of("unresolvedSeeds", List.of("QuantumCache"), "components", List.of(Map.of("typeName", "x"))))))));

        assertThat(result.verdict()).isEqualTo(GateResult.Verdict.FAIL);
        assertThat(result.summary()).contains("QuantumCache");
    }

    @Test
    void failingTestsAreACodeDefectWithSuspects() {
        VerificationReport report = new VerificationReport(VerificationReport.BUILD, false, "TESTS_FAILED", "mvnw test", 10, 1, 0, 0, 1,
                List.of(new FailedTest("demo.FeatureTest", "t", "expected 2")), List.of(), List.of(), List.of("impl-stats"),
                List.of(), "", "hash", "");
        GateResult result = gates.testsPass(withOutputs(Map.of(ArtifactKeys.VERIFICATION_REPORT, OutputArtifact.of("verification", report))));

        assertThat(result.failureKind()).isEqualTo(FailureKind.CODE_DEFECT);
        assertThat(result.suspects()).containsExactly("impl-stats");
        assertThat(result.details()).containsExactly("demo.FeatureTest.t: expected 2");
    }

    @Test
    void releaseChecklistBlocksWhenAnyItemFails() {
        GateResult result = gates.readinessChecklist(withOutputs(Map.of(ArtifactKeys.RELEASE_READINESS, OutputArtifact.of("readiness", Map.of(
                "ready", false, "items", List.of(
                        Map.of("id", "tests-executed-and-passing", "passed", false, "evidence", "STATIC NOT_BUILT"),
                        Map.of("id", "security-review-clean", "passed", true, "evidence", "CLEAN")))))));

        assertThat(result.verdict()).isEqualTo(GateResult.Verdict.FAIL);
        assertThat(result.failureKind()).isEqualTo(FailureKind.FATAL);
        assertThat(result.details()).containsExactly("tests-executed-and-passing: STATIC NOT_BUILT");
    }

    @Test
    void workspaceIntegrityDetectsOutOfBandEdits() throws Exception {
        assertThat(gates.workspaceIntegrity(new GateContext(run, task, new TaskState("task"), null, workspace)).verdict())
                .isEqualTo(GateResult.Verdict.PASS);

        Files.writeString(workspace.root().resolve("src/test/java/demo/Sneaky.java"), "class Sneaky {}\n");

        GateResult result = gates.workspaceIntegrity(new GateContext(run, task, new TaskState("task"), null, workspace));
        assertThat(result.verdict()).isEqualTo(GateResult.Verdict.FAIL);
        assertThat(result.details()).containsExactly("src/test/java/demo/Sneaky.java was changed outside governance");
    }
}
