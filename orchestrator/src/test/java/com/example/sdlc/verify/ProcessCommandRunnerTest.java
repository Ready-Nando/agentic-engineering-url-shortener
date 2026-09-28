package com.example.sdlc.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(WINDOWS)
class ProcessCommandRunnerTest {

    private static final Duration GENEROUS = Duration.ofSeconds(30);

    @TempDir
    Path workingDirectory;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void capturesExitCodeAndInterleavedStdoutAndStderr() {
        CommandResult result = runner.run(List.of("sh", "-c", "echo hi; echo oops >&2; exit 3"),
                workingDirectory, GENEROUS);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).isEqualTo("hi\noops\n");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void passesArgumentsVerbatimWithoutAShell() {
        CommandResult result = runner.run(List.of("echo", "$HOME", "a b;c"), workingDirectory, GENEROUS);

        assertThat(result.output()).isEqualTo("$HOME a b;c\n");
    }

    @Test
    void runsInTheWorkingDirectory() throws IOException {
        CommandResult result = runner.run(List.of("pwd", "-P"), workingDirectory, GENEROUS);

        assertThat(result.output().strip()).isEqualTo(workingDirectory.toRealPath().toString());
    }

    @Test
    void childSeesEndOfInputInsteadOfWaitingForIt() {
        CommandResult result = runner.run(List.of("cat"), workingDirectory, GENEROUS);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void killsCommandThatOutlivesTheTimeout() {
        long start = System.nanoTime();

        CommandResult result = runner.run(List.of("sleep", "5"), workingDirectory, Duration.ofMillis(200));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isNotZero();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        assertThat(result.duration()).isLessThanOrEqualTo(elapsed);
    }

    @Test
    void killsGrandchildrenOnTimeout() {
        // The shell stays alive as the parent of a background sleep that shares its output pipe.
        CommandResult result = runner.run(List.of("sh", "-c", "sleep 60 & echo $!; wait"),
                workingDirectory, Duration.ofSeconds(1));

        assertThat(result.timedOut()).isTrue();
        long sleepPid = Long.parseLong(result.output().strip());
        ProcessHandle.of(sleepPid).ifPresent(sleep ->
                assertThat(sleep.onExit()).succeedsWithin(Duration.ofSeconds(10)));
    }

    @Test
    void keepsOnlyTheTailOfLargeOutputStartingAtALineBoundary() {
        // About 590 KB, far beyond an OS pipe buffer: only finishes if output is drained while the child runs.
        CommandResult result = new ProcessCommandRunner(1024)
                .run(List.of("seq", "1", "100000"), workingDirectory, GENEROUS);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output().length()).isLessThanOrEqualTo(1024);
        List<Integer> numbers = result.output().lines().map(Integer::valueOf).toList();
        assertThat(numbers.getLast()).isEqualTo(100_000);
        assertThat(numbers.getFirst()).isGreaterThan(99_800);
        assertThat(numbers).isSorted().doesNotHaveDuplicates().hasSize(100_000 - numbers.getFirst() + 1);
    }

    @Test
    void failsWhenTheCommandCannotBeStarted() {
        assertThatThrownBy(() -> runner.run(List.of("no-such-command-4135db"), workingDirectory, GENEROUS))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("no-such-command-4135db");
    }

    @Test
    void passesOnlyTheVariablesABuildNeeds() {
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin", "HOME", "/home/dev", "JAVA_HOME", "/opt/jdk", "http_proxy", "http://proxy:3128",
                "HTTPS_PROXY", "http://proxy:3128", "PAYMENT_API_KEY", "not-a-real-key",
                "AWS_SECRET_ACCESS_KEY", "not-a-real-secret", "GITHUB_TOKEN", "not-a-real-token", "MVNW_PASSWORD", "hunter2");

        assertThat(ProcessCommandRunner.inheritedEnvironment(parent)).containsOnlyKeys(
                "PATH", "HOME", "JAVA_HOME", "http_proxy", "HTTPS_PROXY");
    }

    @Test
    void childProcessSeesOnlyTheInheritedVariables() {
        CommandResult result = runner.run(List.of("env"), workingDirectory, GENEROUS);

        List<String> names = result.output().lines()
                .filter(line -> line.matches("[A-Za-z_][A-Za-z0-9_]*=.*"))
                .map(line -> line.substring(0, line.indexOf('=')))
                .toList();
        assertThat(names).isNotEmpty().allSatisfy(name ->
                assertThat(ProcessCommandRunner.inheritedEnvironment(Map.of(name, "value"))).as(name).isNotEmpty());
    }
}
