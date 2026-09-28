package com.example.sdlc.engine;

import java.util.List;

import com.example.sdlc.human.HumanRequest;

/**
 * @param suspects for {@link FailureKind#CODE_DEFECT}: the upstream tasks the evidence points at; empty means
 *                 "unknown", in which case every verified task is reworked
 */
public record GateResult(Verdict verdict, String summary, List<String> details, FailureKind failureKind, HumanNeed humanNeed,
                         List<String> suspects) {

    public enum Verdict { PASS, FAIL, NEEDS_HUMAN }

    /** What to ask a human when the verdict is {@link Verdict#NEEDS_HUMAN}. */
    public record HumanNeed(HumanRequest.Kind kind, String title, List<String> details, List<HumanRequest.Question> questions) {
    }

    public GateResult {
        details = details == null ? List.of() : List.copyOf(details);
        suspects = suspects == null ? List.of() : List.copyOf(suspects);
    }

    public static GateResult pass(String summary) {
        return new GateResult(Verdict.PASS, summary, List.of(), null, null, List.of());
    }

    public static GateResult fail(String summary, List<String> details) {
        return new GateResult(Verdict.FAIL, summary, details, FailureKind.GATE_FAILED, null, List.of());
    }

    /** Failure caused by a defect in upstream work; triggers rework of the suspects (or all verified tasks). */
    public static GateResult defect(String summary, List<String> details, List<String> suspects) {
        return new GateResult(Verdict.FAIL, summary, details, FailureKind.CODE_DEFECT, null, suspects);
    }

    /** Failure that no retry can fix (e.g. release checklist not satisfied). */
    public static GateResult block(String summary, List<String> details) {
        return new GateResult(Verdict.FAIL, summary, details, FailureKind.FATAL, null, List.of());
    }

    public static GateResult approval(String title, List<String> details) {
        return new GateResult(Verdict.NEEDS_HUMAN, title, details, null,
                new HumanNeed(HumanRequest.Kind.GATE_APPROVAL, title, details, List.of()), List.of());
    }

    public static GateResult clarification(String title, List<String> details, List<HumanRequest.Question> questions) {
        return new GateResult(Verdict.NEEDS_HUMAN, title, details, null,
                new HumanNeed(HumanRequest.Kind.CLARIFICATION, title, details, questions), List.of());
    }
}
