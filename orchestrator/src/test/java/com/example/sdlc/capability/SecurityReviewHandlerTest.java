package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityFixtures.approval;
import static com.example.sdlc.capability.CapabilityFixtures.changes;
import static com.example.sdlc.capability.CapabilityFixtures.context;
import static com.example.sdlc.capability.CapabilityFixtures.output;
import static com.example.sdlc.capability.CapabilityFixtures.strings;
import static com.example.sdlc.capability.CapabilityFixtures.visible;
import static com.example.sdlc.capability.CapabilityFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/** The aggregate security review: approval-requiring findings must be backed by an approval for that task and rule. */
class SecurityReviewHandlerTest {

    private static final String MIGRATION = "src/main/resources/db/migration/V2__add_expiry.sql";

    @TempDir
    Path temp;

    private Workspace workspace;
    private final SecurityReviewHandler handler = new SecurityReviewHandler(new ChangePolicy());

    @BeforeEach
    void setUp() throws Exception {
        workspace = CapabilityFixtures.workspace(temp, Map.of(
                "src/main/java/demo/App.java", "package demo;\n\nclass App {\n}\n",
                "src/main/resources/db/migration/V1__init.sql", "CREATE TABLE t (id BIGINT);\n"));
    }

    private JsonNode review(Artifact... artifacts) {
        return output(handler.execute(context("security", "review-security", workspace, visible(artifacts))), ArtifactKeys.SECURITY_REVIEW);
    }

    private void addMigration() throws Exception {
        write(workspace, MIGRATION, "ALTER TABLE t ADD COLUMN expires_at TIMESTAMP;\n");
    }

    @Test
    void approvalRequiringChangeWithoutARecordedApprovalIsAnOpenIssue() throws Exception {
        addMigration();

        JsonNode review = review(changes("impl", 1, MIGRATION));

        assertThat(review.path("status").asString()).isEqualTo("ISSUES");
        assertThat(strings(review.path("unapproved"))).singleElement().asString()
                .contains("CC-02").contains(MIGRATION).endsWith("(introduced by impl)");
        assertThat(review.path("approvedFindings")).isEmpty();
    }

    @Test
    void approvalForAnotherTaskDoesNotCoverTheFinding() throws Exception {
        addMigration();

        JsonNode review = review(changes("impl", 1, MIGRATION), approval("hr-1", "other-task", "CC-02"));

        assertThat(review.path("status").asString()).isEqualTo("ISSUES");
        assertThat(strings(review.path("unapproved"))).singleElement().asString().contains("CC-02");
    }

    @Test
    void approvalOfAnotherRuleDoesNotCoverTheFinding() throws Exception {
        addMigration();

        JsonNode review = review(changes("impl", 1, MIGRATION), approval("hr-1", "impl", "SEC-04"));

        assertThat(review.path("status").asString()).isEqualTo("ISSUES");
        assertThat(strings(review.path("unapproved"))).singleElement().asString().contains("CC-02");
    }

    @Test
    void approvalForTheIntroducingTaskAndRuleMakesTheReviewClean() throws Exception {
        addMigration();

        JsonNode review = review(changes("impl", 1, MIGRATION), approval("hr-1", "impl", "CC-02"));

        assertThat(review.path("status").asString()).isEqualTo("CLEAN");
        assertThat(review.path("unapproved")).isEmpty();
        assertThat(review.path("blocking")).isEmpty();
        assertThat(strings(review.path("approvedFindings"))).singleElement().asString()
                .contains("CC-02").endsWith("(introduced by impl)");
    }

    @Test
    void findingThatOnlyExistsInTheAggregateIsSurfacedWithoutBlocking() throws Exception {
        List<String> paths = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            String path = "src/main/java/demo/gen/Part" + i + ".java";
            write(workspace, path, "package demo.gen;\n\nclass Part" + i + " {\n}\n");
            paths.add(path);
        }

        JsonNode review = review(changes("impl", 1, paths.subList(0, 8).toArray(String[]::new)),
                changes("tests", 2, paths.subList(8, 16).toArray(String[]::new)));

        assertThat(review.path("filesReviewed").asInt()).isEqualTo(16);
        assertThat(strings(review.path("aggregateFindings"))).singleElement().asString().contains("CC-07").contains("16 files");
        assertThat(review.path("unapproved")).isEmpty();
        assertThat(review.path("blocking")).isEmpty();
        assertThat(review.path("status").asString()).isEqualTo("CLEAN");
    }
}
