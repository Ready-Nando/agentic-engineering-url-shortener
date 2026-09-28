package com.example.sdlc.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;

class ScriptedReviewerTest {

    private static HumanRequest request(HumanRequest.Kind kind, String task, int generation, List<String> rules) {
        return new HumanRequest("hr-1", kind, task, generation, 1, null, "title", List.of(), List.of(), rules,
                HumanRequest.Status.PENDING, null, Instant.now(), null);
    }

    private final ScriptedReviewer reviewer = new ScriptedReviewer(new ScriptedReviewer.Script("bob", List.of(
            new ScriptedReviewer.Rule(new ScriptedReviewer.Match("GATE_APPROVAL", "release", null, null, 1),
                    HumanResponse.Decision.REQUEST_CHANGES, "make it configurable", "design", Map.of()),
            new ScriptedReviewer.Rule(new ScriptedReviewer.Match("GATE_APPROVAL", "release", null, null, null),
                    HumanResponse.Decision.APPROVE, "ship it", null, Map.of()),
            new ScriptedReviewer.Rule(new ScriptedReviewer.Match("CHANGE_APPROVAL", null, null, List.of("CC-02"), null),
                    HumanResponse.Decision.APPROVE, "additive migration", null, Map.of()))));

    @Test
    void firstMatchingRuleDecides() {
        assertThat(reviewer.respond(request(HumanRequest.Kind.GATE_APPROVAL, "release", 1, List.of())))
                .hasValueSatisfying(r -> {
                    assertThat(r.decision()).isEqualTo(HumanResponse.Decision.REQUEST_CHANGES);
                    assertThat(r.changeTarget()).isEqualTo("design");
                    assertThat(r.reviewer()).isEqualTo("bob");
                });
        assertThat(reviewer.respond(request(HumanRequest.Kind.GATE_APPROVAL, "release", 2, List.of())))
                .hasValueSatisfying(r -> assertThat(r.decision()).isEqualTo(HumanResponse.Decision.APPROVE));
    }

    @Test
    void matchesOnPolicyRulesAndLeavesUncoveredCheckpointsUnanswered() {
        assertThat(reviewer.respond(request(HumanRequest.Kind.CHANGE_APPROVAL, "impl", 1, List.of("CC-02")))).isPresent();
        assertThat(reviewer.respond(request(HumanRequest.Kind.CHANGE_APPROVAL, "impl", 1, List.of("CC-02", "CC-06"))))
                .as("an approval scoped to CC-02 must not also cover CC-06").isEmpty();
        assertThat(reviewer.respond(request(HumanRequest.Kind.CHANGE_APPROVAL, "impl", 1, List.of("CC-04")))).isEmpty();
        assertThat(reviewer.respond(request(HumanRequest.Kind.CLARIFICATION, "requirements", 1, List.of()))).isEmpty();
    }
}
