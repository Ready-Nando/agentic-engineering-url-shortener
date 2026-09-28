package com.example.sdlc.engine;

import java.util.List;

/** Thrown by handlers (trusted engine-side code) to report a classified failure with feedback details. */
public class TaskFailure extends RuntimeException {

    private final FailureKind kind;
    private final List<String> details;

    public TaskFailure(FailureKind kind, String message, List<String> details) {
        super(message);
        this.kind = kind;
        this.details = List.copyOf(details);
    }

    public TaskFailure(FailureKind kind, String message) {
        this(kind, message, List.of());
    }

    public FailureKind kind() {
        return kind;
    }

    public List<String> details() {
        return details;
    }
}
