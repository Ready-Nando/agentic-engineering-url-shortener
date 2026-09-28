package com.example.sdlc.plan;

import java.util.List;

/** Structured output of the planning capability: the proposed decomposition and why. */
public record PlanProposal(String rationale, List<TaskSpec> tasks) {

    public PlanProposal {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
