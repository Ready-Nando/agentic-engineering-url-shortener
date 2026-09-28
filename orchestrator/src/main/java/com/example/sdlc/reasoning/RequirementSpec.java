package com.example.sdlc.reasoning;

import java.util.List;

/**
 * Normalised engineering problem produced from a raw requirement. Everything the reasoning component is
 * unsure about must surface as an assumption or open question; deterministic gates decide what blocks.
 */
public record RequirementSpec(
        String title,
        String summary,
        String changeType,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<String> constraints,
        List<Assumption> assumptions,
        List<String> outOfScope,
        List<OpenQuestion> openQuestions,
        List<Risk> risks) {

    public RequirementSpec {
        acceptanceCriteria = nullToEmpty(acceptanceCriteria);
        constraints = nullToEmpty(constraints);
        assumptions = nullToEmpty(assumptions);
        outOfScope = nullToEmpty(outOfScope);
        openQuestions = nullToEmpty(openQuestions);
        risks = nullToEmpty(risks);
    }

    public record AcceptanceCriterion(String id, String statement, String verification) {
    }

    /** {@code confirmed} is true only when a human confirmed it (e.g. through a clarification answer). */
    public record Assumption(String id, String statement, Impact impact, boolean confirmed) {
    }

    public record OpenQuestion(String id, String question, String rationale, List<Option> options, boolean blocking) {
        public OpenQuestion {
            options = nullToEmpty(options);
        }
    }

    public record Option(String id, String label, String consequence) {
    }

    public record Risk(String id, String description, Impact severity, String mitigation) {
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
