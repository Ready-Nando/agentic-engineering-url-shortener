package com.example.sdlc.engine;

/**
 * Classification that drives recovery. The engine classifies untyped errors itself; trusted handlers may
 * throw a {@link TaskFailure} with an explicit kind. Reasoning providers can only signal "unavailable" or
 * "invalid output" - neither lets them bypass gates or policy.
 */
public enum FailureKind {
    /** Infrastructure hiccup (process timeout, tool unavailable, provider error). Retry, then fallback. */
    TRANSIENT(true, true),
    /** The capability cannot run in this environment (e.g. no build tool). No retry; go straight to fallback. */
    UNAVAILABLE(false, true),
    /** Reasoning output violated its structured contract or could not be applied. Retry with feedback, then fallback. */
    INVALID_OUTPUT(true, true),
    /** Governance policy denied the proposed action. Retry with the violations as feedback. */
    POLICY_DENIED(true, false),
    /** An exit gate rejected the result. Retry with the gate findings as feedback. */
    GATE_FAILED(true, false),
    /** Verification found a defect in upstream work: rework the producers rather than retrying the verifier. */
    CODE_DEFECT(false, false),
    /** Concurrent modification detected on apply. Retry against fresh content. */
    CONFLICT(true, false),
    /** A reviewer asked for changes to this task's result. */
    CHANGES_REQUESTED(true, false),
    /** A reviewer rejected the action. Never retried. */
    REJECTED(false, false),
    /** No reasoning response is available (e.g. the offline provider has no recording). */
    REASONING_UNAVAILABLE(false, false),
    /** Unexpected error; treated as unrecoverable. */
    FATAL(false, false);

    private final boolean retryable;
    private final boolean fallbackEligible;

    FailureKind(boolean retryable, boolean fallbackEligible) {
        this.retryable = retryable;
        this.fallbackEligible = fallbackEligible;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean fallbackEligible() {
        return fallbackEligible;
    }
}
