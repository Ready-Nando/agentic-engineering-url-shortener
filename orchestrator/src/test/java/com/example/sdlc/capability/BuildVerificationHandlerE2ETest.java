package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityFixtures.changes;
import static com.example.sdlc.capability.CapabilityFixtures.context;
import static com.example.sdlc.capability.CapabilityFixtures.visible;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.verify.FailedTest;
import com.example.sdlc.verify.MavenBuildVerifier;
import com.example.sdlc.verify.ProcessCommandRunner;
import com.example.sdlc.workspace.ChangeSet;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.Workspace;

/**
 * Suspect attribution against the real shortener build: a documentation change set breaks the OpenAPI document,
 * the shortener's own contract test catches it, and the failure is attributed to that change set alone. Slow
 * (a nested Maven build), so it is only part of the {@code e2e} profile.
 */
@Tag("e2e")
class BuildVerificationHandlerE2ETest {

    private static final String STATS_PATH = "  /api/v1/links/{code}/stats:\n";
    private static final String NEXT_PATH = "  /{code}:\n";

    @TempDir
    Path temp;

    @Test
    void contractTestFailingOnADocumentationChangeImplicatesOnlyTheDocumentationTask() {
        Path repository = locateRepository();
        Map<Path, String> wrapper = new LinkedHashMap<>();
        for (String file : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            wrapper.put(repository.resolve(file), file);
        }
        Workspace workspace = Workspace.create(repository.resolve("shortener"), wrapper, temp.resolve("run"));
        String openApi = workspace.read(WorkspaceFacts.OPENAPI).orElseThrow();
        String statsOperation = openApi.substring(openApi.indexOf(STATS_PATH), openApi.indexOf(NEXT_PATH));
        // A harmless production change applied alongside, so attribution has to choose between two change sets.
        workspace.apply("cs-1", "impl", 1, new ChangeSet("helper", "LOW", List.of(FileChange.create(
                "src/main/java/com/example/shortener/link/LinkCodes.java",
                "package com.example.shortener.link;\n\nfinal class LinkCodes {\n\n    private LinkCodes() {\n    }\n}\n")), Map.of()));
        workspace.apply("cs-2", "docs", 1, new ChangeSet("drop stats from the document", "LOW",
                List.of(FileChange.edit(WorkspaceFacts.OPENAPI, statsOperation, "")), Map.of()));

        BuildVerificationHandler handler = new BuildVerificationHandler(
                new MavenBuildVerifier(new ProcessCommandRunner(), Duration.ofMinutes(5)), false);
        VerificationReport report = handler.execute(context("verify", "verify-build", workspace, visible(
                        changes("impl", 1, "src/main/java/com/example/shortener/link/LinkCodes.java"),
                        changes("docs", 2, WorkspaceFacts.OPENAPI))))
                .outputs().get(ArtifactKeys.VERIFICATION_REPORT).as(VerificationReport.class);

        assertThat(report.outcome()).as(report.outputTail()).isEqualTo("TESTS_FAILED");
        assertThat(report.failedTests()).isNotEmpty().extracting(FailedTest::className)
                .containsOnly("com.example.shortener.OpenApiContractTest");
        assertThat(report.failedTests()).extracting(FailedTest::testName)
                .contains("specDocumentsExactlyTheEndpointsTheApplicationExposes");
        assertThat(report.suspects()).containsExactly("docs");
        assertThat(report.workspaceHash()).isEqualTo(workspace.contentHash());
        assertThat(workspace.read(WorkspaceFacts.OPENAPI).orElseThrow()).doesNotContain(STATS_PATH);
    }

    private static Path locateRepository() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("scenarios")) && Files.isDirectory(dir.resolve("shortener"))) {
                return dir;
            }
        }
        throw new IllegalStateException("repository root not found");
    }
}
