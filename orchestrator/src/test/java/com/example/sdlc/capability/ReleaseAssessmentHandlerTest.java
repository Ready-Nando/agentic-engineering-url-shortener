package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityFixtures.artifact;
import static com.example.sdlc.capability.CapabilityFixtures.changes;
import static com.example.sdlc.capability.CapabilityFixtures.context;
import static com.example.sdlc.capability.CapabilityFixtures.output;
import static com.example.sdlc.capability.CapabilityFixtures.visible;
import static com.example.sdlc.capability.CapabilityFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/** The release checklist is computed from evidence only; each item fails on the evidence it guards. */
class ReleaseAssessmentHandlerTest {

    private static final String APP_TEST = "demo.AppTest";
    private static final String CODEC_TEST = "demo.CodecTest";

    @TempDir
    Path temp;

    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = CapabilityFixtures.workspace(temp, Map.of("src/main/java/demo/App.java", "package demo;\n\nclass App {\n}\n"));
        write(workspace, "src/main/java/demo/Feature.java", "package demo;\n\nclass Feature {\n}\n");
    }

    private static VerificationReport build(int testsRun, List<String> passedClasses, String workspaceHash) {
        return new VerificationReport(VerificationReport.BUILD, false, "PASSED", "mvnw test", testsRun, 0, 0, 0, 1000,
                List.of(), passedClasses, List.of(new VerificationReport.Coverage("AC-1", List.of(APP_TEST), List.of(APP_TEST), true)),
                List.of(), List.of(), "", workspaceHash, "");
    }

    private static VerificationReport degraded(String workspaceHash) {
        return new VerificationReport(VerificationReport.STATIC, true, "NOT_BUILT", "", 0, 0, 0, 0, 0, List.of(), List.of(),
                List.of(new VerificationReport.Coverage("AC-1", List.of(APP_TEST), List.of(), true)), List.of(), List.of(), "",
                workspaceHash, "static checks only: tests were not executed");
    }

    private JsonNode assess(VerificationReport verification) {
        return assess(verification, changes("impl", 1, "src/main/java/demo/Feature.java"));
    }

    /** Assessment with passing build and security evidence plus the given change sets, reviews and design artifacts. */
    private JsonNode assess(VerificationReport verification, Artifact... evidence) {
        VerificationReport baseline = build(10, List.of(APP_TEST, CODEC_TEST), workspace.baselineHash());
        Map<String, Artifact> visible = visible(
                artifact(ArtifactKeys.BASELINE_VERIFICATION, baseline, "baseline"),
                artifact(ArtifactKeys.VERIFICATION_REPORT, verification, "verify"),
                artifact(ArtifactKeys.SECURITY_REVIEW, Map.of("status", "CLEAN", "approvedFindings", List.of()), "security"));
        visible.putAll(visible(evidence));
        return output(new ReleaseAssessmentHandler().execute(context("release", "assess-release", workspace, visible)),
                ArtifactKeys.RELEASE_READINESS);
    }

    private JsonNode assessWithEvidence(Artifact... evidence) {
        return assess(build(12, List.of(APP_TEST, CODEC_TEST), workspace.contentHash()), evidence);
    }

    /** A design contract whose author labels every operation as unchanged. */
    private static Artifact contractClaimingNoApiChange() {
        return artifact(ArtifactKeys.API_CONTRACT, Map.of("operations", List.of(
                Map.of("method", "GET", "path", "/api/v1/links/{code}", "change", "UNCHANGED"),
                Map.of("method", "POST", "path", "/api/v1/links", "change", "UNCHANGED"))), "design");
    }

    private static Artifact apiReview(String status, String... addedOperations) {
        return artifact(ArtifactKeys.API_COMPATIBILITY, Map.of("status", status, "addedOperations", List.of(addedOperations),
                "breakingChanges", List.of(), "undocumented", List.of(), "unimplemented", List.of()), "api-review");
    }

    private static JsonNode item(JsonNode readiness, String id) {
        for (JsonNode item : readiness.path("items")) {
            if (item.path("id").asString().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("no checklist item " + id);
    }

    private static List<String> failedItems(JsonNode readiness) {
        return StreamSupport.stream(readiness.path("items").spliterator(), false)
                .filter(i -> !i.path("passed").asBoolean()).map(i -> i.path("id").asString()).toList();
    }

    @Test
    void completeEvidenceForTheReleasedTreeIsReady() {
        JsonNode readiness = assess(build(12, List.of(APP_TEST, CODEC_TEST), workspace.contentHash()));

        assertThat(failedItems(readiness)).isEmpty();
        assertThat(readiness.path("ready").asBoolean()).isTrue();
    }

    @Test
    void verifiedTreeMustBeTheTreeBeingReleased() {
        String verified = "0".repeat(64);

        JsonNode readiness = assess(build(12, List.of(APP_TEST, CODEC_TEST), verified));

        assertThat(failedItems(readiness)).containsExactly("verified-tree-is-final");
        assertThat(item(readiness, "verified-tree-is-final").path("evidence").asString())
                .isEqualTo("verified 000000000000 but releasing " + workspace.contentHash().substring(0, 12));
        assertThat(readiness.path("ready").asBoolean()).isFalse();
    }

    @Test
    void baselineTestClassThatNoLongerPassesIsARegression() {
        JsonNode readiness = assess(build(12, List.of(APP_TEST), workspace.contentHash()));

        assertThat(failedItems(readiness)).containsExactly("no-test-regression");
        assertThat(item(readiness, "no-test-regression").path("evidence").asString()).isEqualTo(CODEC_TEST + " no longer passes");
        assertThat(readiness.path("ready").asBoolean()).isFalse();
    }

    @Test
    void fewerExecutedTestsThanTheBaselineIsARegression() {
        JsonNode readiness = assess(build(8, List.of(APP_TEST, CODEC_TEST), workspace.contentHash()));

        assertThat(failedItems(readiness)).containsExactly("no-test-regression");
        assertThat(item(readiness, "no-test-regression").path("evidence").asString()).isEqualTo("executed tests dropped from 10 to 8");
        assertThat(readiness.path("ready").asBoolean()).isFalse();
    }

    @Test
    void degradedVerificationNeverCountsAsExecutedTests() {
        JsonNode readiness = assess(degraded(workspace.contentHash()));

        assertThat(item(readiness, "tests-executed-and-passing").path("passed").asBoolean()).isFalse();
        assertThat(item(readiness, "tests-executed-and-passing").path("evidence").asString()).contains("STATIC NOT_BUILT").contains("DEGRADED");
        assertThat(item(readiness, "verified-tree-is-final").path("passed").asBoolean()).isTrue();
        assertThat(readiness.path("ready").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java/demo/api/LinkResponse.java", "src/main/java/demo/web/ApiExceptionHandler.java",
            "src/main/java/demo/LinkController.java", "src/main/resources/static/openapi.yaml"})
    void apiRelevantChangeWithoutAnApiReviewIsNotReadyWhateverTheDesignLabelsSay(String apiPath) {
        JsonNode readiness = assessWithEvidence(contractClaimingNoApiChange(),
                changes("impl", 1, "src/main/java/demo/Feature.java", apiPath));

        assertThat(failedItems(readiness)).containsExactly("api-compatible-and-documented");
        assertThat(item(readiness, "api-compatible-and-documented").path("evidence").asString())
                .isEqualTo("not reviewed, but changed [" + apiPath + "]");
        assertThat(readiness.path("ready").asBoolean()).isFalse();
    }

    @Test
    void withoutAnApiReviewOnlyChangesThatCannotTouchTheApiPass() {
        JsonNode readiness = assessWithEvidence(contractClaimingNoApiChange(),
                changes("impl", 1, "src/main/java/demo/Feature.java", "src/main/java/demo/apiary/Hive.java",
                        "src/main/resources/application.yml"),
                changes("tests", 2, "src/test/java/demo/api/LinkApiTest.java"));

        assertThat(item(readiness, "api-compatible-and-documented").path("passed").asBoolean()).isTrue();
        assertThat(item(readiness, "api-compatible-and-documented").path("evidence").asString())
                .isEqualTo("not reviewed; no API-relevant file changed");
        assertThat(failedItems(readiness)).isEmpty();
        assertThat(readiness.path("ready").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BREAKING", "INCOMPLETE"})
    void apiReviewThatIsNotCompatibleIsNotReady(String status) {
        JsonNode readiness = assessWithEvidence(contractClaimingNoApiChange(), apiReview(status),
                changes("impl", 1, "src/main/java/demo/Feature.java"));

        assertThat(failedItems(readiness)).containsExactly("api-compatible-and-documented");
        assertThat(item(readiness, "api-compatible-and-documented").path("evidence").asString()).startsWith(status + ", added");
        assertThat(readiness.path("ready").asBoolean()).isFalse();
    }

    @Test
    void operationsTheApiReviewFoundAddedMustBeDocumentedWhateverTheDesignLabelsSay() {
        // Pins the rule that labels cannot waive what the review measured. In a run the review only finds added
        // operations in a changed OpenAPI document, which itself counts as documentation, so this evidence is
        // assembled by hand rather than produced by the API review.
        Artifact review = apiReview("COMPATIBLE", "POST /api/v1/links/{code}/reports");

        JsonNode undocumented = assessWithEvidence(contractClaimingNoApiChange(), review,
                changes("impl", 1, "src/main/java/demo/Feature.java"));
        JsonNode documented = assessWithEvidence(contractClaimingNoApiChange(), review,
                changes("impl", 1, "src/main/java/demo/Feature.java"), changes("docs", 2, "README.md"));

        assertThat(failedItems(undocumented)).containsExactly("documentation-updated");
        assertThat(item(undocumented, "documentation-updated").path("evidence").asString())
                .isEqualTo("API changed but no documentation change");
        assertThat(undocumented.path("ready").asBoolean()).isFalse();
        assertThat(failedItems(documented)).isEmpty();
        assertThat(documented.path("ready").asBoolean()).isTrue();
    }
}
