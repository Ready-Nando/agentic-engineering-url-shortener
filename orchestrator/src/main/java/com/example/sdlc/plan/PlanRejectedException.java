package com.example.sdlc.plan;

import java.util.List;

/** A proposed plan violated structural or governance rules; the violations are fed back to the planner. */
public class PlanRejectedException extends RuntimeException {

    private final List<String> violations;

    public PlanRejectedException(List<String> violations) {
        super("plan rejected: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
