package com.example.sdlc.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;

class InteractiveReviewerTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private InteractiveReviewer reviewer(String input) {
        return new InteractiveReviewer(new BufferedReader(new StringReader(input)), new PrintStream(output), "erin");
    }

    private static HumanRequest request(HumanRequest.Kind kind, List<HumanRequest.Question> questions) {
        return new HumanRequest("hr-2", kind, "release", 1, 1, "release-approval", "Release sign-off", List.of("PASS tests"),
                questions, List.of(), HumanRequest.Status.PENDING, null, Instant.now(), null);
    }

    @Test
    void approvalsAndChangeRequestsAreRecordedWithTheReviewersName() {
        assertThat(reviewer("a\nlooks good\n").respond(request(HumanRequest.Kind.GATE_APPROVAL, List.of())))
                .hasValue(HumanResponse.approve("erin", "looks good"));
        assertThat(reviewer("c\ndesign\nmake it configurable\n").respond(request(HumanRequest.Kind.GATE_APPROVAL, List.of())))
                .hasValue(HumanResponse.requestChanges("erin", "make it configurable", "design"));
        assertThat(output.toString()).contains("Release sign-off", "PASS tests");
    }

    @Test
    void clarificationsCollectOneAnswerPerQuestionAndLaterDefers() {
        List<HumanRequest.Question> questions = List.of(
                new HumanRequest.Question("Q-1", "Which links expire?", "", List.of(new HumanRequest.Option("per-link", "Per link", "")), false),
                new HumanRequest.Question("Q-2", "Status?", "", List.of(), true));

        assertThat(reviewer("per-link\n410 Gone\n").respond(request(HumanRequest.Kind.CLARIFICATION, questions)))
                .hasValueSatisfying(r -> assertThat(r.answers()).containsEntry("Q-1", "per-link").containsEntry("Q-2", "410 Gone"));
        assertThat(reviewer("later\n").respond(request(HumanRequest.Kind.CLARIFICATION, questions))).isEmpty();
        assertThat(reviewer("").respond(request(HumanRequest.Kind.GATE_APPROVAL, List.of()))).as("closed input defers").isEmpty();
    }
}
