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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.verify.CommandResult;
import com.example.sdlc.verify.CommandRunner;
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
