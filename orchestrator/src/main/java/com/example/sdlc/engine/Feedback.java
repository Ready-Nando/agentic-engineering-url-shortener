package com.example.sdlc.engine;

import java.util.List;

/** Structured feedback handed to the next attempt of a task (why the previous one was not accepted). */
public record Feedback(String source, FailureKind kind, String message, List<String> details) {

    public Feedback {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
