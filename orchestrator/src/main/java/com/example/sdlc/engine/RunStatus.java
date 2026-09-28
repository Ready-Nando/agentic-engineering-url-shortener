package com.example.sdlc.engine;

public enum RunStatus {
    CREATED,
    RUNNING,
    /** Safely paused: nothing can progress until a human answers a pending request. Resumable. */
    AWAITING_HUMAN,
    /** Every task succeeded, including release sign-off. */
    COMPLETED,
    /** Safe stop after an unrecoverable failure, rejection or budget exhaustion; workspace changes compensated. */
    HALTED;

    public boolean isFinal() {
        return this == COMPLETED || this == HALTED;
    }
}
