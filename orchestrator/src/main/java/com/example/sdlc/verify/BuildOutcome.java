package com.example.sdlc.verify;

public enum BuildOutcome {
    PASSED,
    TESTS_FAILED,
    COMPILATION_FAILED,
    /** No build process was started (e.g. no wrapper, or it could not be launched). Retrying will not help. */
    NOT_RUN,
    /** The build ran but could not judge the code: dependency resolution, JDK problems and the like. */
    INFRASTRUCTURE_ERROR,
    TIMED_OUT;

    /** Whether the failure is the code's fault, i.e. worth handing back to whoever changed it. */
    public boolean attributableToCode() {
        return this == TESTS_FAILED || this == COMPILATION_FAILED;
    }
}
