package com.example.sdlc.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

class MavenBuildVerifierTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Duration BUILD_TIME = Duration.ofSeconds(42);
    private static final String APP_TEST = "com.example.shortener.AppTest";
    private static final String CODEC_TEST = "com.example.shortener.CodecTest";

    @TempDir
    Path project;

    private final List<Invocation> invocations = new ArrayList<>();

    private record Invocation(List<String> command, Path workingDirectory, Duration timeout, boolean staleReports) {
    }

    @BeforeEach
    void createWrapper() throws IOException {
        // Both names, so the tests do not depend on which one the host OS picks.
        for (String name : List.of("mvnw", "mvnw.cmd")) {
            Path wrapper = Files.writeString(project.resolve(name), "#!/bin/sh\n");
            wrapper.toFile().setExecutable(true);
        }
    }

    @Test
    void passingBuild() {
        BuildReport report = verify(fakeMaven(0, "[INFO] BUILD SUCCESS",
                suite(APP_TEST, passing(APP_TEST, "starts"), passing(APP_TEST, "stops")),
                suite(CODEC_TEST, passing(CODEC_TEST, "roundTrips"))));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.PASSED);
        assertThat(report.exitCode()).isZero();
        assertThat(report.testsRun()).isEqualTo(3);
        assertThat(report.failedTests()).isEmpty();
        assertThat(report.passedTestClasses()).containsExactly(APP_TEST, CODEC_TEST);
        assertThat(report.executedTestClasses()).containsExactly(APP_TEST, CODEC_TEST);
        assertThat(report.duration()).isEqualTo(BUILD_TIME);
        assertThat(report.outputTail()).isEqualTo("[INFO] BUILD SUCCESS");

        Invocation invocation = invocations.getFirst();
        assertThat(invocations).hasSize(1);
        assertThat(Path.of(invocation.command().getFirst()).getParent()).isEqualTo(project);
        assertThat(invocation.command().subList(1, invocation.command().size())).containsExactly("-B", "-ntp", "test");
        assertThat(invocation.workingDirectory()).isEqualTo(project);
        assertThat(invocation.timeout()).isEqualTo(TIMEOUT);
        assertThat(report.command()).contains("-B -ntp test");
    }

    @Test
    void passingBuildWithoutTestsIsStillPassed() {
        BuildReport report = verify(fakeMaven(0, "[INFO] No tests to run.\n[INFO] BUILD SUCCESS"));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.PASSED);
        assertThat(report.testsRun()).isZero();
        assertThat(report.executedTestClasses()).isEmpty();
    }

    @Test
    void failingTests() {
        BuildReport report = verify(fakeMaven(1, "[ERROR] There are test failures.\n[INFO] BUILD FAILURE",
                suite(APP_TEST, passing(APP_TEST, "starts"), failing(APP_TEST, "stops", "expected: <0> but was: <1>")),
                suite(CODEC_TEST, passing(CODEC_TEST, "roundTrips"))));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.TESTS_FAILED);
        assertThat(report.outcome().attributableToCode()).isTrue();
        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(report.testsRun()).isEqualTo(3);
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.failedTests())
                .containsExactly(new FailedTest(APP_TEST, "stops", "expected: <0> but was: <1>"));
        assertThat(report.passedTestClasses()).containsExactly(CODEC_TEST);
        assertThat(report.executedTestClasses()).containsExactly(APP_TEST, CODEC_TEST);
    }

    @Test
    void erroringTestsAreTestFailures() {
        BuildReport report = verify(fakeMaven(1, "[INFO] BUILD FAILURE",
                suite(APP_TEST, erroring(APP_TEST, "starts", "NullPointerException"))));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.TESTS_FAILED);
        assertThat(report.failures()).isZero();
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.failedTests()).containsExactly(new FailedTest(APP_TEST, "starts", "NullPointerException"));
        assertThat(report.passedTestClasses()).isEmpty();
    }

    @Test
    void failingTestsCountEvenWhenMavenIgnoresThem() {
        BuildReport report = verify(fakeMaven(0, "[INFO] BUILD SUCCESS",
                suite(APP_TEST, failing(APP_TEST, "stops", "boom"))));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.TESTS_FAILED);
    }

    @Test
    void failingTestsWinOverCompilerWordsInTestOutput() {
        BuildReport report = verify(fakeMaven(1, "checking input: Compilation failure expected\n[INFO] BUILD FAILURE",
                suite(APP_TEST, failing(APP_TEST, "compiles", "boom"))));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.TESTS_FAILED);
    }

    @Test
    void compilationFailure() {
        String output = """
                [INFO] --- compiler:3.14.0:compile (default-compile) @ shortener ---
                [ERROR] COMPILATION ERROR :
                [ERROR] /work/src/main/java/com/example/shortener/App.java:[14,9] cannot find symbol
                [INFO] BUILD FAILURE
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.0:compile \
                (default-compile) on project shortener: Compilation failure
                """;

        BuildReport report = verify(fakeMaven(1, output));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.COMPILATION_FAILED);
        assertThat(report.outcome().attributableToCode()).isTrue();
        assertThat(report.testsRun()).isZero();
        assertThat(report.outputTail()).contains("cannot find symbol");
    }

    @Test
    void infrastructureError() {
        BuildReport report = verify(fakeMaven(1, """
                [ERROR] Failed to execute goal on project shortener: Could not resolve dependencies for project \
                com.example:shortener:jar:0.1.0-SNAPSHOT
                """));

        assertThat(report.outcome()).isEqualTo(BuildOutcome.INFRASTRUCTURE_ERROR);
        assertThat(report.outcome().attributableToCode()).isFalse();
        assertThat(report.exitCode()).isEqualTo(1);
    }

    @Test
    void timeout() {
        CommandRunner runner = (command, workingDirectory, timeout) -> {
            writeReports(workingDirectory, List.of(suite(APP_TEST, passing(APP_TEST, "starts"))));
            return new CommandResult(137, "[INFO] Running com.example.shortener.SlowTest", timeout, true);
        };

        BuildReport report = verify(runner);

        assertThat(report.outcome()).isEqualTo(BuildOutcome.TIMED_OUT);
        assertThat(report.outcome().attributableToCode()).isFalse();
        assertThat(report.duration()).isEqualTo(TIMEOUT);
        assertThat(report.executedTestClasses()).containsExactly(APP_TEST);
    }

    @Test
    void missingWrapperMeansNothingRan() throws IOException {
        Files.delete(project.resolve("mvnw"));
        Files.delete(project.resolve("mvnw.cmd"));

        BuildReport report = verify(fakeMaven(0, "should not run"));

        assertThat(invocations).isEmpty();
        assertThat(report.outcome()).isEqualTo(BuildOutcome.NOT_RUN);
        assertThat(report.exitCode()).isEqualTo(-1);
        assertThat(report.duration()).isEqualTo(Duration.ZERO);
        assertThat(report.outputTail()).startsWith("Maven wrapper not found");
    }

    @Test
    void runnerThatCannotStartTheWrapperMeansNothingRan() {
        CommandRunner runner = (command, workingDirectory, timeout) -> {
            throw new UncheckedIOException("Could not start mvnw: error=13, Permission denied", new IOException());
        };

        BuildReport report = verify(runner);

        assertThat(report.outcome()).isEqualTo(BuildOutcome.NOT_RUN);
        assertThat(report.outputTail()).contains("Permission denied");
    }

    @Test
    void staleReportsAreRemovedBeforeTheBuild() throws IOException {
        Path reports = Files.createDirectories(project.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-com.example.shortener.OldTest.xml"),
                suite("com.example.shortener.OldTest", failing("com.example.shortener.OldTest", "old", "stale")));

        BuildReport report = verify(fakeMaven(0, "[INFO] BUILD SUCCESS"));

        assertThat(invocations.getFirst().staleReports()).isFalse();
        assertThat(report.outcome()).isEqualTo(BuildOutcome.PASSED);
        assertThat(report.testsRun()).isZero();
        assertThat(report.executedTestClasses()).isEmpty();
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void symlinkedReportsDirectoryIsUnlinkedNotEmptied(@TempDir Path elsewhere) throws IOException {
        Path precious = Files.writeString(elsewhere.resolve("TEST-keep.xml"), "not ours to delete");
        Files.createDirectories(project.resolve("target"));
        Files.createSymbolicLink(project.resolve("target/surefire-reports"), elsewhere);

        BuildReport report = verify(fakeMaven(0, "[INFO] BUILD SUCCESS"));

        assertThat(invocations.getFirst().staleReports()).isFalse();
        assertThat(precious).exists();
        assertThat(report.outcome()).isEqualTo(BuildOutcome.PASSED);
    }

    @Test
    void customArgumentsReplaceTheDefaults() {
        var verifier = new MavenBuildVerifier(fakeMaven(0, ""), TIMEOUT, List.of("-B", "-o", "verify"));

        BuildReport report = verifier.verify(project);

        List<String> command = invocations.getFirst().command();
        assertThat(command.subList(1, command.size())).containsExactly("-B", "-o", "verify");
        assertThat(report.command()).endsWith(" -B -o verify");
    }

    @Test
    void outputTailKeepsTheLastSixtyLines() {
        String output = IntStream.rangeClosed(1, 100).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));

        BuildReport report = verify(fakeMaven(1, output));

        assertThat(report.outputTail().lines().toList())
                .hasSize(60)
                .startsWith("line 41")
                .endsWith("line 100");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void makesTheWrapperExecutable() throws IOException {
        Path wrapper = project.resolve("mvnw");
        wrapper.toFile().setExecutable(false);
        assertThat(Files.isExecutable(wrapper)).isFalse();

        BuildReport report = verify(fakeMaven(0, ""));

        assertThat(Files.isExecutable(wrapper)).isTrue();
        assertThat(report.outcome()).isEqualTo(BuildOutcome.PASSED);
    }

    private BuildReport verify(CommandRunner runner) {
        return new MavenBuildVerifier(runner, TIMEOUT).verify(project);
    }

    /** Pretends to be Maven: records the call, writes the given Surefire reports and exits. */
    private CommandRunner fakeMaven(int exitCode, String output, String... reports) {
        return (command, workingDirectory, timeout) -> {
            boolean staleReports = Files.exists(workingDirectory.resolve("target/surefire-reports"));
            invocations.add(new Invocation(command, workingDirectory, timeout, staleReports));
            writeReports(workingDirectory, Arrays.asList(reports));
            return new CommandResult(exitCode, output, BUILD_TIME, false);
        };
    }

    private static void writeReports(Path projectDirectory, List<String> reports) {
        try {
            Path directory = Files.createDirectories(projectDirectory.resolve("target/surefire-reports"));
            for (int i = 0; i < reports.size(); i++) {
                Files.writeString(directory.resolve("TEST-" + i + ".xml"), reports.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String suite(String className, String... testcases) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="%s" tests="%d" failures="%d" errors="%d" skipped="0">
                %s
                </testsuite>
                """.formatted(className, testcases.length,
                Arrays.stream(testcases).filter(testcase -> testcase.contains("<failure")).count(),
                Arrays.stream(testcases).filter(testcase -> testcase.contains("<error")).count(),
                String.join("\n", testcases));
    }

    private static String passing(String className, String name) {
        return "<testcase classname=\"%s\" name=\"%s\" time=\"0.01\"/>".formatted(className, name);
    }

    private static String failing(String className, String name, String message) {
        return problem("failure", className, name, message);
    }

    private static String erroring(String className, String name, String message) {
        return problem("error", className, name, message);
    }

    private static String problem(String kind, String className, String name, String message) {
        String escaped = message.replace("<", "&lt;").replace(">", "&gt;");
        return """
                <testcase classname="%2$s" name="%3$s" time="0.01">\
                <%1$s message="%4$s" type="java.lang.Throwable">stack trace</%1$s>\
                </testcase>""".formatted(kind, className, name, escaped);
    }
}
