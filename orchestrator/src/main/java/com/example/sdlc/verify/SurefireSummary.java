package com.example.sdlc.verify;

import java.util.List;

/**
 * Totals across all readable Surefire reports. Test classes are fully qualified names; a class counts as
 * passed when at least one of its tests ran and none failed or errored. A class whose tests were all skipped
 * is executed but not passed.
 */
public record SurefireSummary(
        int testsRun,
        int failures,
        int errors,
        int skipped,
        List<FailedTest> failedTests,
        List<String> passedTestClasses,
        List<String> executedTestClasses) {

    public static final SurefireSummary EMPTY = new SurefireSummary(0, 0, 0, 0, List.of(), List.of(), List.of());

    public SurefireSummary {
        failedTests = List.copyOf(failedTests);
        passedTestClasses = List.copyOf(passedTestClasses);
        executedTestClasses = List.copyOf(executedTestClasses);
    }
}
