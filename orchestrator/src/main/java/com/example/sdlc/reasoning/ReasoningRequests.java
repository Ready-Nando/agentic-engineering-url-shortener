package com.example.sdlc.reasoning;

import java.util.List;
import java.util.Map;

import com.example.sdlc.engine.Feedback;

import tools.jackson.databind.JsonNode;

/**
 * Structured inputs at the reasoning boundary. {@code invocation} is the task's execution count within the
 * run (stable across resumes), which lets recorded providers replay deterministically.
 */
public final class ReasoningRequests {

    private ReasoningRequests() {
    }

    public record RequirementRequest(String taskId, int invocation, String requirement, Map<String, String> answers,
                                     List<Feedback> feedback) {
    }

    public record ImpactRequest(String taskId, int invocation, RequirementSpec spec, String codebaseSummary,
                                List<Feedback> feedback) {
    }

    public record PlanRequest(String taskId, int invocation, RequirementSpec spec, JsonNode impact,
                              List<String> capabilities, List<Feedback> feedback) {
    }

    public record DesignRequest(String taskId, int invocation, RequirementSpec spec, JsonNode impact,
                                List<Feedback> feedback) {
    }

    public record ChangeRequest(String taskId, int invocation, String goal, List<String> scope, RequirementSpec spec,
                                Map<String, JsonNode> design, Map<String, String> files, List<Feedback> feedback) {
    }
}
