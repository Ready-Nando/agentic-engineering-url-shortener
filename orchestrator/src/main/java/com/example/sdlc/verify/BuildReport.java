package com.example.sdlc.verify;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What a verification run established. {@code exitCode} is -1 when no process exit status is available (not
 * started, or not reaped after a timeout); {@code outputTail} holds the last lines of build output, or the
 * reason the build could not be started.
 */
public record BuildReport(
        BuildOutcome outcome,
        int exitCode,
        int testsRun,
        int failures,
        int errors,
        int skipped,
        List<FailedTest> failedTests,
        List<String> passedTestClasses,
        List<String> executedTestClasses,
        String command,
        Duration duration,
        String outputTail) {

    public BuildReport {
        Objects.requireNonNull(outcome, "outcome");
        failedTests = List.copyOf(failedTests);
        passedTestClasses = List.copyOf(passedTestClasses);
        executedTestClasses = List.copyOf(executedTestClasses);
    }
}
