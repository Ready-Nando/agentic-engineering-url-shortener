package com.example.sdlc.engine;

import java.time.Instant;

/** Audit record of one execution attempt of a task. */
public record AttemptRecord(
        int generation,
        int attempt,
        String capability,
        boolean fallback,
        Instant startedAt,
        Instant endedAt,
        Outcome outcome,
        FailureKind failureKind,
        String message) {

    public enum Outcome { SUCCEEDED, FAILED, DISCARDED, CANCELLED }
}
