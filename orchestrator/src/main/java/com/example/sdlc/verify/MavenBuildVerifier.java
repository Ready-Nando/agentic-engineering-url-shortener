package com.example.sdlc.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verifies a single-module Maven project by running its own test suite through its Maven wrapper, then
 * classifies the result from the exit code, the build output and the Surefire reports.
 */
public final class MavenBuildVerifier {

    public static final List<String> DEFAULT_ARGUMENTS = List.of("-B", "-ntp", "test");

    private static final int OUTPUT_TAIL_LINES = 60;
    private static final List<String> COMPILATION_MARKERS = List.of("COMPILATION ERROR", "Compilation failure");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final CommandRunner runner;
    private final Duration timeout;
    private final List<String> arguments;

    public MavenBuildVerifier(CommandRunner runner, Duration timeout) {
        this(runner, timeout, DEFAULT_ARGUMENTS);
    }

    /** @param arguments everything passed to the wrapper (goals, flags, properties), replacing the defaults */
    public MavenBuildVerifier(CommandRunner runner, Duration timeout, List<String> arguments) {
        this.runner = Objects.requireNonNull(runner, "runner");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        this.timeout = timeout;
        this.arguments = List.copyOf(arguments);
    }

    public BuildReport verify(Path projectDirectory) {
        Path project = projectDirectory.toAbsolutePath().normalize();
        Path wrapper = project.resolve(WINDOWS ? "mvnw.cmd" : "mvnw");
        List<String> command = new ArrayList<>();
        command.add(wrapper.toString());
        command.addAll(arguments);
        String commandLine = display(command);

        if (!Files.isRegularFile(wrapper)) {
            return notRun(commandLine, "Maven wrapper not found: " + wrapper);
        }
        // Copies made without preserving permissions lose the executable bit.
        if (!Files.isExecutable(wrapper) && !wrapper.toFile().setExecutable(true)) {
            return notRun(commandLine, "Maven wrapper is not executable and could not be made so: " + wrapper);
        }
        Path reportsDirectory = project.resolve("target").resolve("surefire-reports");
        try {
            deleteRecursively(reportsDirectory);
        } catch (IOException | UncheckedIOException e) {
            return notRun(commandLine, "Could not remove stale Surefire reports in " + reportsDirectory + ": " + e);
        }

        CommandResult result;
        try {
            result = runner.run(List.copyOf(command), project, timeout);
        } catch (UncheckedIOException e) {
            return notRun(commandLine, e.getMessage());
        }
        SurefireSummary tests = SurefireReports.parse(reportsDirectory);
        return new BuildReport(
                classify(result, tests),
                result.exitCode(),
                tests.testsRun(),
                tests.failures(),
                tests.errors(),
                tests.skipped(),
                tests.failedTests(),
                tests.passedTestClasses(),
                tests.executedTestClasses(),
                commandLine,
                result.duration(),
                tail(result.output()));
    }

    private static BuildOutcome classify(CommandResult result, SurefireSummary tests) {
        if (result.timedOut()) {
            return BuildOutcome.TIMED_OUT;
        }
        // Reports are wiped before every run, so failures in them come from this build. They outrank a zero
        // exit code (testFailureIgnore would otherwise let failing tests pass) and outrank the compiler
        // markers, which test output could print; tests that ran with failures compiled fine.
        if (tests.failures() + tests.errors() > 0) {
            return BuildOutcome.TESTS_FAILED;
        }
        if (result.exitCode() == 0) {
            return BuildOutcome.PASSED;
        }
        if (COMPILATION_MARKERS.stream().anyMatch(result.output()::contains)) {
            return BuildOutcome.COMPILATION_FAILED;
        }
        return BuildOutcome.INFRASTRUCTURE_ERROR;
    }

    private static BuildReport notRun(String commandLine, String reason) {
        return new BuildReport(BuildOutcome.NOT_RUN, -1, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), commandLine, Duration.ZERO, reason);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // Files.walk does not follow links, so a symlinked reports directory is unlinked, not emptied.
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String display(List<String> command) {
        return command.stream()
                .map(arg -> arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace) ? "'" + arg + "'" : arg)
                .collect(Collectors.joining(" "));
    }

    private static String tail(String output) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - OUTPUT_TAIL_LINES), lines.size()));
    }
}
