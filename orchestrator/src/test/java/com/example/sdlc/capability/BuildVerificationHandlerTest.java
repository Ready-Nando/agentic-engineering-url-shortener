package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityFixtures.changes;
import static com.example.sdlc.capability.CapabilityFixtures.context;
import static com.example.sdlc.capability.CapabilityFixtures.visible;
import static com.example.sdlc.capability.CapabilityFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.verify.CommandResult;
import com.example.sdlc.verify.CommandRunner;
import com.example.sdlc.verify.FailedTest;
import com.example.sdlc.verify.MavenBuildVerifier;
import com.example.sdlc.workspace.Workspace;

/**
 * Suspect attribution after a failed build, driven through the real {@link MavenBuildVerifier} with a fake
 * Maven process (the {@link CommandRunner} boundary) that writes Surefire reports into the workspace.
 */
class BuildVerificationHandlerTest {

    @TempDir
    Path temp;

    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = CapabilityFixtures.workspace(temp, Map.of(
                "mvnw", "#!/bin/sh\n",
                "mvnw.cmd", "@echo off\n",
                "src/main/java/demo/App.java", "package demo;\n\nclass App {\n}\n",
                "src/main/java/demo/Legacy.java", "package demo;\n\nclass Legacy {\n    int uniqueVisitors;\n}\n"));
        workspace.root().resolve("mvnw").toFile().setExecutable(true);
    }

    /** Pretends to be Maven: writes the given Surefire reports into the project and exits. */
    private static CommandRunner fakeMaven(int exitCode, String output, String... reports) {
        return (command, workingDirectory, timeout) -> {
            try {
                Path directory = Files.createDirectories(workingDirectory.resolve("target/surefire-reports"));
                for (int i = 0; i < reports.length; i++) {
                    Files.writeString(directory.resolve("TEST-" + i + ".xml"), reports[i]);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new CommandResult(exitCode, output, Duration.ofSeconds(3), false);
        };
    }

    private static String failingSuite(String className, String test, String message) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="%1$s" tests="2" failures="1" errors="0" skipped="0">
                <testcase classname="%1$s" name="ok" time="0.01"/>
                <testcase classname="%1$s" name="%2$s" time="0.01"><failure message="%3$s" type="java.lang.AssertionError">trace</failure></testcase>
                </testsuite>
                """.formatted(className, test, message);
    }

    private VerificationReport verify(CommandRunner maven, Artifact... changeSets) {
        BuildVerificationHandler handler = new BuildVerificationHandler(new MavenBuildVerifier(maven, Duration.ofMinutes(1)), false);
        TaskResult result = handler.execute(context("verify", "verify-build", workspace, visible(changeSets)));
        return result.outputs().get(ArtifactKeys.VERIFICATION_REPORT).as(VerificationReport.class);
    }

    @Test
    void compilerErrorsPointAtTheChangeSetThatWroteTheFile() throws Exception {
        write(workspace, "src/main/java/demo/Feature.java", "package demo;\n\nclass Feature { Missing m; }\n");
        write(workspace, "src/main/java/demo/Other.java", "package demo;\n\nclass Other {\n}\n");
        String output = """
                [INFO] --- compiler:3.14.0:compile (default-compile) @ shortener ---
                [ERROR] COMPILATION ERROR :
                [ERROR] %s/src/main/java/demo/Feature.java:[3,17] cannot find symbol
                [INFO] BUILD FAILURE
                """.formatted(workspace.root());

        VerificationReport report = verify(fakeMaven(1, output),
                changes("implA", 1, "src/main/java/demo/Feature.java"), changes("implB", 2, "src/main/java/demo/Other.java"));

        assertThat(report.outcome()).isEqualTo("COMPILATION_FAILED");
        assertThat(report.suspects()).containsExactly("implA");
    }

    @Test
    void symbolQuotedByAFailingTestPointsAtTheChangeSetThatIntroducedItIntoCode() throws Exception {
        write(workspace, "src/main/java/demo/Stats.java", "package demo;\n\nclass Stats {\n    long uniqueVisitors;\n}\n");
        write(workspace, "docs/stats.md", "The response now includes uniqueVisitors.\n");
        // Legacy already declared the symbol in the baseline: editing it does not introduce it.
        write(workspace, "src/main/java/demo/Legacy.java", "package demo;\n\nclass Legacy {\n    int uniqueVisitors;\n    int other;\n}\n");
        String report = failingSuite("demo.StatsApiTest", "countsVisitors", "JSON path $.uniqueVisitors expected 2 but was 1");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("docs", 1, "docs/stats.md"),
                changes("legacy", 2, "src/main/java/demo/Legacy.java"),
                changes("stats", 3, "src/main/java/demo/Stats.java"));

        assertThat(result.outcome()).isEqualTo("TESTS_FAILED");
        assertThat(result.suspects()).containsExactly("stats");
    }

    @Test
    void contractTestFailingOnADocumentationTypoPointsOnlyAtTheDocumentation() throws Exception {
        write(workspace, "src/main/resources/static/openapi.yaml", "openapi: 3.0.3\npaths: {}\n");
        write(workspace, "src/main/java/demo/Stats.java", "package demo;\n\nclass Stats {\n    long uniqueVisitors;\n}\n");
        write(workspace, "src/test/java/demo/OpenApiContractTest.java", """
                package demo;

                class OpenApiContractTest {
                    String spec = new String(getClass().getClassLoader().getResourceAsStream("classpath:static/openapi.yaml").readAllBytes());
                }
                """);
        // The document misspells a field the baseline already had: nothing the stats change introduced is quoted.
        String report = failingSuite("demo.OpenApiContractTest", "specDocumentsEveryField",
                "LinkStats: missing in the spec [totalClicks], missing in the record [totalClikcs]");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("stats", 1, "src/main/java/demo/Stats.java"),
                changes("docs", 2, "src/main/resources/static/openapi.yaml", "README.md"),
                changes("tests", 3, "src/test/java/demo/OpenApiContractTest.java"));

        assertThat(result.outcome()).isEqualTo("TESTS_FAILED");
        assertThat(result.suspects()).containsExactly("docs");
    }

    @Test
    void contractTestFailingOnAnUndocumentedFieldAlsoPointsAtTheImplementationThatAddedIt() throws Exception {
        // The document is fine; the implementation added a response field nobody documented. Blaming only the
        // document would rework it unchanged until the attempt budget runs out.
        write(workspace, "src/main/resources/static/openapi.yaml", "openapi: 3.0.3\npaths: {}\n");
        write(workspace, "src/main/java/demo/LinkStatsResponse.java",
                "package demo;\n\nrecord LinkStatsResponse(long totalClicks, long distinctVisitorHashes) {\n}\n");
        write(workspace, "src/test/java/demo/OpenApiContractTest.java", """
                package demo;

                class OpenApiContractTest {
                    String spec = new String(getClass().getClassLoader().getResourceAsStream("static/openapi.yaml").readAllBytes());
                }
                """);
        String report = failingSuite("demo.OpenApiContractTest", "specDocumentsExactlyTheFieldsOfEveryJsonBody",
                "LinkStatsResponse: missing in the spec [distinctVisitorHashes], missing in the record []");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("impl-stats", 1, "src/main/java/demo/LinkStatsResponse.java"),
                changes("docs", 2, "src/main/resources/static/openapi.yaml"));

        assertThat(result.suspects()).containsExactlyInAnyOrder("impl-stats", "docs");
    }

    @Test
    void aResourceWhoseNameIsOnlyASuffixOfTheLoadedFileIsNoEvidence() throws Exception {
        write(workspace, "src/main/resources/messages.properties", "greeting=hello\n");
        write(workspace, "src/main/java/demo/Stats.java", "package demo;\n\nclass Stats {\n    long uniqueVisitors;\n}\n");
        write(workspace, "src/test/java/demo/StatsApiTest.java", """
                package demo;

                class StatsApiTest {
                    String messages = "classpath:validation-messages.properties";
                }
                """);
        String report = failingSuite("demo.StatsApiTest", "countsVisitors", "JSON path $.uniqueVisitors expected 2 but was 1");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("i18n", 1, "src/main/resources/messages.properties"),
                changes("stats", 2, "src/main/java/demo/Stats.java"));

        assertThat(result.suspects()).containsExactly("stats");
    }

    @Test
    void aResourceMentionedOnlyInACommentIsNoEvidence() throws Exception {
        write(workspace, "src/main/resources/application.yml", "server:\n  port: 8081\n");
        write(workspace, "src/main/java/demo/Stats.java", "package demo;\n\nclass Stats {\n    long uniqueVisitors;\n}\n");
        write(workspace, "src/test/java/demo/StatsApiTest.java", """
                package demo;

                // Runs with the port from application.yml, see "application.yml".
                class StatsApiTest {
                    /* The profile in "application.yml" is not loaded here. */
                    char quote = '"';
                    String path = "/api/v1/links/abc/stats";
                }
                """);
        String report = failingSuite("demo.StatsApiTest", "countsVisitors", "JSON path $.uniqueVisitors expected 2 but was 1");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("config", 1, "src/main/resources/application.yml"),
                changes("stats", 2, "src/main/java/demo/Stats.java"));

        assertThat(result.suspects()).containsExactly("stats");
    }

    @Test
    void contractTestFailingOnATypoInAFieldTheRunIntroducedAlsoPointsAtTheImplementation() throws Exception {
        // Deliberate trade-off of the union rule: the message quotes both spellings, and the correct one is a
        // symbol the implementation introduced, so the implementation is reworked alongside the document.
        write(workspace, "src/main/resources/static/openapi.yaml", "openapi: 3.0.3\npaths: {}\n");
        write(workspace, "src/main/java/demo/LinkStatsResponse.java",
                "package demo;\n\nrecord LinkStatsResponse(long totalClicks, long uniqueVisitors) {\n}\n");
        write(workspace, "src/test/java/demo/OpenApiContractTest.java", """
                package demo;

                class OpenApiContractTest {
                    String spec = new String(getClass().getClassLoader().getResourceAsStream("static/openapi.yaml").readAllBytes());
                }
                """);
        String report = failingSuite("demo.OpenApiContractTest", "specDocumentsExactlyTheFieldsOfEveryJsonBody",
                "LinkStatsResponse: missing in the spec [uniqueVisitors], missing in the record [uniqeVisitors]");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("impl-stats", 1, "src/main/java/demo/LinkStatsResponse.java"),
                changes("docs", 2, "src/main/resources/static/openapi.yaml"));

        assertThat(result.suspects()).containsExactlyInAnyOrder("docs", "impl-stats");
    }

    @Test
    void aLongFixtureLiteralInTheFailingTestDoesNotFailTheVerificationOnAWorkerThread() throws Exception {
        write(workspace, "src/main/resources/static/openapi.yaml", "openapi: 3.0.3\npaths: {}\n");
        write(workspace, "src/main/java/demo/Stats.java", "package demo;\n\nclass Stats {\n    long uniqueVisitors;\n}\n");
        write(workspace, "src/test/java/demo/OpenApiContractTest.java", """
                package demo;

                class OpenApiContractTest {
                    String blob = "%s\\"";
                    String spec = new String(getClass().getClassLoader().getResourceAsStream("static/openapi.yaml").readAllBytes());
                }
                """.formatted("QUJD".repeat(25_000)));
        String report = failingSuite("demo.OpenApiContractTest", "specDocumentsEveryField",
                "LinkStats: missing in the spec [totalClicks], missing in the record [totalClikcs]");
        CommandRunner maven = fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report);

        // The engine runs handlers on default-sized platform threads, whose stack is smaller than the test runner's.
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().name("worker-1").start(() -> {
            try {
                outcome.set(verify(maven, changes("stats", 1, "src/main/java/demo/Stats.java"),
                        changes("docs", 2, "src/main/resources/static/openapi.yaml")));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        worker.join();

        assertThat(outcome.get()).isInstanceOfSatisfying(VerificationReport.class,
                result -> assertThat(result.suspects()).containsExactly("docs"));
    }

    @Test
    void aChangedResourceExplainsOnlyTheTestsThatLoadIt() throws Exception {
        // The implementation forgot the field the document now describes: the contract test and the
        // integration test fail together, and each points at a different change set.
        write(workspace, "src/main/resources/static/openapi.yaml", "openapi: 3.0.3\npaths: {}\n");
        write(workspace, "src/main/java/demo/LinkStatsService.java", "package demo;\n\nclass LinkStatsService {\n    long clicks;\n}\n");
        write(workspace, "src/test/java/demo/OpenApiContractTest.java", """
                package demo;

                class OpenApiContractTest {
                    String spec = new String(getClass().getClassLoader().getResourceAsStream("static/openapi.yaml").readAllBytes());
                }
                """);
        write(workspace, "src/test/java/demo/StatsIntegrationTest.java", """
                package demo;

                class StatsIntegrationTest {
                    LinkStatsService service = new LinkStatsService();
                }
                """);

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 4, Failures: 2\n[INFO] BUILD FAILURE",
                        failingSuite("demo.OpenApiContractTest", "specDocumentsEveryField",
                                "LinkStats: missing in the spec [], missing in the record [uniqueVisitors]"),
                        failingSuite("demo.StatsIntegrationTest", "countsVisitors", "No value at JSON path $.uniqueVisitors")),
                changes("impl-stats", 1, "src/main/java/demo/LinkStatsService.java"),
                changes("docs", 2, "src/main/resources/static/openapi.yaml"));

        assertThat(result.failedTests()).extracting(FailedTest::className)
                .containsExactlyInAnyOrder("demo.OpenApiContractTest", "demo.StatsIntegrationTest");
        assertThat(result.suspects()).containsExactlyInAnyOrder("docs", "impl-stats");
    }

    @Test
    void changedResourcesTheFailingTestDoesNotLoadAreNoEvidence() throws Exception {
        write(workspace, "src/main/resources/static/openapi.yaml", "openapi: 3.0.3\npaths: {}\n");
        write(workspace, "src/main/java/demo/Stats.java", "package demo;\n\nclass Stats {\n    long uniqueVisitors;\n}\n");
        write(workspace, "src/test/java/demo/StatsApiTest.java", """
                package demo;

                // Reads its own fixture, src/test/resources/static/openapi-sample.yaml, and the README.md example.
                class StatsApiTest {
                }
                """);
        String report = failingSuite("demo.StatsApiTest", "countsVisitors", "JSON path $.uniqueVisitors expected 2 but was 1");

        VerificationReport result = verify(fakeMaven(1, "[ERROR] Tests run: 2, Failures: 1\n[INFO] BUILD FAILURE", report),
                changes("stats", 1, "src/main/java/demo/Stats.java"),
                changes("docs", 2, "src/main/resources/static/openapi.yaml", "README.md"));

        assertThat(result.suspects()).containsExactly("stats");
    }

    @Test
    void typesReferencedByTheFailingTestPointAtTheChangeSetsThatWroteThem() throws Exception {
        write(workspace, "src/main/java/demo/LinkService.java", "package demo;\n\nclass LinkService {\n}\n");
        write(workspace, "src/main/java/demo/Other.java", "package demo;\n\nclass Other {\n}\n");
        write(workspace, "src/test/java/demo/ExpiryTest.java",
                "package demo;\n\nclass ExpiryTest {\n    LinkService service = new LinkService();\n}\n");
        String report = failingSuite("demo.ExpiryTest", "expiresLinks", "expected: [true] but was: [false]");

        VerificationReport result = verify(fakeMaven(1, "[INFO] BUILD FAILURE", report),
                changes("service", 1, "src/main/java/demo/LinkService.java"),
                changes("other", 2, "src/main/java/demo/Other.java"),
                changes("tests", 3, "src/test/java/demo/ExpiryTest.java"));

        assertThat(result.outcome()).isEqualTo("TESTS_FAILED");
        assertThat(result.failedTests()).singleElement().satisfies(t -> assertThat(t.className()).isEqualTo("demo.ExpiryTest"));
        assertThat(result.suspects()).containsExactly("service");
    }

    @Test
    void noEvidenceMeansNoSuspects() throws Exception {
        write(workspace, "src/main/java/demo/Other.java", "package demo;\n\nclass Other {\n}\n");
        String report = failingSuite("demo.UnrelatedTest", "fails", "expected: [1] but was: [2]");

        VerificationReport result = verify(fakeMaven(1, "[INFO] BUILD FAILURE", report), changes("other", 1, "src/main/java/demo/Other.java"));

        assertThat(result.outcome()).isEqualTo("TESTS_FAILED");
        assertThat(result.suspects()).as("unknown: the engine then reworks every verified task").isEmpty();
        assertThat(result.workspaceHash()).isEqualTo(workspace.contentHash());
        assertThat(result.mode()).isEqualTo(VerificationReport.BUILD);
        assertThat(result.degraded()).isFalse();
    }
}
