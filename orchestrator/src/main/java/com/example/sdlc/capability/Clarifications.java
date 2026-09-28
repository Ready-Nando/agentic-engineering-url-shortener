package com.example.sdlc.capability;

import java.util.List;
import java.util.Map;

import com.example.sdlc.human.HumanRequest;

/** Content of the human-produced {@code requirement/clarifications} artifact. */
public record Clarifications(Map<String, String> answers, List<HumanRequest.Question> questions, String answeredBy) {

    public Clarifications {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
