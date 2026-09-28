package com.example.sdlc.workspace;

/** A change could not be resolved or applied (missing anchor, conflicting concurrent edit, integrity failure). */
public class WorkspaceException extends RuntimeException {

    public enum Reason { INVALID_CHANGE, CONFLICT, INTEGRITY }

    private final Reason reason;

    public WorkspaceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
