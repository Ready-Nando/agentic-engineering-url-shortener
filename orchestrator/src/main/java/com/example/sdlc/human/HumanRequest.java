package com.example.sdlc.human;

import java.time.Instant;
import java.util.List;

/**
 * A checkpoint where the engine needs a human: approval of a high-impact action or result, or clarification
 * of an ambiguous requirement.
 *
 * @param gate the exit gate that raised an approval, or {@code null} for change-set approvals
 */
public record HumanRequest(
        String id,
        Kind kind,
        String taskId,
        int generation,
        int attempt,
        String gate,
        String title,
        List<String> details,
        List<Question> questions,
        List<String> policyRules,
        Status status,
        HumanResponse response,
        Instant requestedAt,
        Instant respondedAt) {

    /** Key prefix of the engine artifact holding the complete diff a change approval was requested for. */
    public static final String CHANGE_REVIEW_PREFIX = "change-review/";

    public enum Kind { CHANGE_APPROVAL, GATE_APPROVAL, CLARIFICATION }

    /** Where the run store writes that diff, relative to the run directory. */
    public static String approvalPatchPath(String requestId) {
        return "approvals/" + requestId + ".patch";
    }

    public enum Status { PENDING, ANSWERED, APPLIED, CANCELLED }

    public record Question(String id, String text, String rationale, List<Option> options, boolean freeTextAllowed) {
        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Option(String id, String label, String consequence) {
    }

    public HumanRequest {
        details = details == null ? List.of() : List.copyOf(details);
        questions = questions == null ? List.of() : List.copyOf(questions);
        policyRules = policyRules == null ? List.of() : List.copyOf(policyRules);
    }

    public HumanRequest answered(HumanResponse newResponse, Instant at) {
        return new HumanRequest(id, kind, taskId, generation, attempt, gate, title, details, questions, policyRules,
                Status.ANSWERED, newResponse, requestedAt, at);
    }

    public HumanRequest withStatus(Status newStatus) {
        return new HumanRequest(id, kind, taskId, generation, attempt, gate, title, details, questions, policyRules,
                newStatus, response, requestedAt, respondedAt);
    }
}
