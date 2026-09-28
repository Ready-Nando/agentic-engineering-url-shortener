package com.example.sdlc.human;

import java.util.Map;

/**
 * A reviewer's decision.
 *
 * @param changeTarget for {@link Decision#REQUEST_CHANGES}: the task whose output should change
 *                     (defaults to the task that raised the request)
 */
public record HumanResponse(Decision decision, String reviewer, String comment, Map<String, String> answers, String changeTarget) {

    public enum Decision { APPROVE, REJECT, REQUEST_CHANGES, ANSWER }

    public HumanResponse {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        comment = comment == null ? "" : comment;
    }

    public static HumanResponse approve(String reviewer, String comment) {
        return new HumanResponse(Decision.APPROVE, reviewer, comment, Map.of(), null);
    }

    public static HumanResponse reject(String reviewer, String comment) {
        return new HumanResponse(Decision.REJECT, reviewer, comment, Map.of(), null);
    }

    public static HumanResponse answer(String reviewer, Map<String, String> answers) {
        return new HumanResponse(Decision.ANSWER, reviewer, "", answers, null);
    }

    public static HumanResponse requestChanges(String reviewer, String comment, String target) {
        return new HumanResponse(Decision.REQUEST_CHANGES, reviewer, comment, Map.of(), target);
    }
}
