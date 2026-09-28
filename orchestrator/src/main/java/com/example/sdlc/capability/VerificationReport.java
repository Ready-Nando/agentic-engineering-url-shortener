package com.example.sdlc.capability;

import java.util.List;

import com.example.sdlc.codebase.LayerViolation;
import com.example.sdlc.verify.FailedTest;

/**
 * Content of {@code verification/report} and {@code verification/baseline}: what verification established and
 * how (a real build, or DEGRADED static checks only). Gates and the release checklist read this type rather
 * than loose JSON fields, so a renamed field is a compile error instead of a silently different verdict.
 *
 * @param suspects tasks the failure evidence points at (empty when unknown)
 * @param workspaceHash the exact source tree that was verified
 */
public record VerificationReport(
        String mode,
        boolean degraded,
        String outcome,
        String command,
        int testsRun,
        int failures,
        int errors,
        int skipped,
        long durationMs,
        List<FailedTest> failedTests,
        List<String> passedTestClasses,
        List<Coverage> acceptanceCoverage,
        List<String> suspects,
        List<LayerViolation> introducedViolations,
        String outputTail,
        String workspaceHash,
        String note) {

    public static final String BUILD = "BUILD";
    public static final String STATIC = "STATIC";

    public record Coverage(String id, List<String> tests, List<String> passingTests, boolean covered) {
        public Coverage {
            tests = tests == null ? List.of() : List.copyOf(tests);
            passingTests = passingTests == null ? List.of() : List.copyOf(passingTests);
        }
    }

    public VerificationReport {
        failedTests = failedTests == null ? List.of() : List.copyOf(failedTests);
        passedTestClasses = passedTestClasses == null ? List.of() : List.copyOf(passedTestClasses);
        acceptanceCoverage = acceptanceCoverage == null ? List.of() : List.copyOf(acceptanceCoverage);
        suspects = suspects == null ? List.of() : List.copyOf(suspects);
        introducedViolations = introducedViolations == null ? List.of() : List.copyOf(introducedViolations);
        outputTail = outputTail == null ? "" : outputTail;
        note = note == null ? "" : note;
    }

    public boolean passedRealBuild() {
        return BUILD.equals(mode) && !degraded && "PASSED".equals(outcome) && testsRun > 0;
    }
}
