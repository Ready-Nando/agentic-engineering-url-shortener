package com.example.sdlc.policy;

public record PolicyFinding(String ruleId, PolicyCategory category, PolicyDecision decision, String path, String message) {

    public String describe() {
        return "[" + ruleId + " " + decision + "] " + (path == null ? "" : path + ": ") + message;
    }
}
