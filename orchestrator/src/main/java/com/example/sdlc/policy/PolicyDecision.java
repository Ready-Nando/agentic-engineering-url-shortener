package com.example.sdlc.policy;

/** Ordered from least to most restrictive; a verdict is the most restrictive decision of any finding. */
public enum PolicyDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY
}
