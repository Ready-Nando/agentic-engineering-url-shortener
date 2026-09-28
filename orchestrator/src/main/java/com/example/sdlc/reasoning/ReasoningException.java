package com.example.sdlc.reasoning;

import java.util.List;

import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.TaskFailure;

public class ReasoningException extends TaskFailure {

    private ReasoningException(FailureKind kind, String message) {
        super(kind, message, List.of());
    }

    public static ReasoningException unavailable(String message) {
        return new ReasoningException(FailureKind.REASONING_UNAVAILABLE, message);
    }

    public static ReasoningException invalid(String message) {
        return new ReasoningException(FailureKind.INVALID_OUTPUT, message);
    }
}
