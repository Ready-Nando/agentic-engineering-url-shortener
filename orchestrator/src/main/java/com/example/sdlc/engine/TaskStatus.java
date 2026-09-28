package com.example.sdlc.engine;

public enum TaskStatus {
    PENDING,
    READY,
    RUNNING,
    AWAITING_HUMAN,
    SUCCEEDED,
    FAILED,
    /** A prerequisite failed, so this task can never run in this run. */
    BLOCKED,
    /** Not started (or result discarded) because the run was safely stopped or the task was removed by a re-plan. */
    CANCELLED,
    /** Completed, but its workspace changes were compensated during a safe stop. */
    ROLLED_BACK;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == BLOCKED || this == CANCELLED || this == ROLLED_BACK;
    }

    public boolean preventsDependents() {
        return this == FAILED || this == BLOCKED || this == CANCELLED || this == ROLLED_BACK;
    }
}
