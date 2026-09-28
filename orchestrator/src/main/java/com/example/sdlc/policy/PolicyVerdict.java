package com.example.sdlc.policy;

import java.util.Comparator;
import java.util.List;

public record PolicyVerdict(PolicyDecision decision, List<PolicyFinding> findings) {

    public PolicyVerdict {
        findings = List.copyOf(findings);
    }

    static PolicyVerdict of(List<PolicyFinding> findings) {
        PolicyDecision decision = findings.stream()
                .map(PolicyFinding::decision)
                .max(Comparator.naturalOrder())
                .orElse(PolicyDecision.ALLOW);
        return new PolicyVerdict(decision, findings);
    }

    public List<String> describe() {
        return findings.stream().map(PolicyFinding::describe).toList();
    }

    public List<String> ruleIds(PolicyDecision atLeast) {
        return findings.stream().filter(f -> f.decision().compareTo(atLeast) >= 0).map(PolicyFinding::ruleId).distinct().toList();
    }
}
