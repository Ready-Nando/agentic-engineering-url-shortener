package com.example.sdlc.scenario;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.sdlc.human.HumanGateway;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;

/**
 * Reproducible stand-in for a human reviewer: answers checkpoints according to the scenario script. A
 * checkpoint the script does not cover is left unanswered, so the run pauses exactly as it would for a real,
 * asynchronous reviewer.
 */
public final class ScriptedReviewer implements HumanGateway {

    /** Decision rules; the first rule whose non-null fields all match the request applies. */
    public record Script(String name, List<Rule> decisions) {
        public Script {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    public record Rule(Match match, HumanResponse.Decision decision, String comment, String target, Map<String, String> answers) {
    }

    /**
     * @param rules for change approvals: the policy rules this decision covers. It only matches a request
     *              whose every approval-requiring rule is in this list, so approving a migration (CC-02) never
     *              silently approves, say, an unanticipated edit (CC-06) bundled with it.
     */
    public record Match(String kind, String task, String gate, List<String> rules, Integer generation) {
    }

    private final Script script;

    public ScriptedReviewer(Script script) {
        this.script = script;
    }

    @Override
    public Optional<HumanResponse> respond(HumanRequest request) {
        return script.decisions().stream()
                .filter(rule -> matches(rule.match(), request))
                .findFirst()
                .map(rule -> new HumanResponse(rule.decision(), script.name(), rule.comment(), rule.answers(), rule.target()));
    }

    private static boolean matches(Match match, HumanRequest request) {
        if (match == null) {
            return false;
        }
        return (match.kind() == null || match.kind().equalsIgnoreCase(request.kind().name()))
                && (match.task() == null || match.task().equals(request.taskId()))
                && (match.gate() == null || match.gate().equals(request.gate()))
                && (match.rules() == null || match.rules().containsAll(request.policyRules()))
                && (match.generation() == null || match.generation() == request.generation());
    }
}
