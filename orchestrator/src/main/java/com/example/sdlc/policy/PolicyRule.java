package com.example.sdlc.policy;

public record PolicyRule(String id, PolicyCategory category, PolicyDecision decision, String description) {
}
