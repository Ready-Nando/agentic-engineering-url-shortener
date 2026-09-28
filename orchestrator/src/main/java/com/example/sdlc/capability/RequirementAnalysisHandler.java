package com.example.sdlc.capability;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.reasoning.ReasoningException;
import com.example.sdlc.reasoning.ReasoningProvider;
import com.example.sdlc.reasoning.ReasoningRequests.RequirementRequest;
import com.example.sdlc.reasoning.RequirementSpec;

/** Normalises the raw requirement (plus any human clarifications) into a structured specification. */
final class RequirementAnalysisHandler implements TaskHandler {

    private final ReasoningProvider reasoning;

    RequirementAnalysisHandler(ReasoningProvider reasoning) {
        this.reasoning = reasoning;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        RequirementSpec spec = reasoning.analyzeRequirement(new RequirementRequest(context.task().id(), context.invocation(),
                context.requirement(), clarificationAnswers(context), context.feedback()));
        validate(spec);
        return TaskResult.of("normalised requirement: " + spec.acceptanceCriteria().size() + " acceptance criteria, "
                        + spec.assumptions().size() + " assumptions, " + spec.openQuestions().size() + " open questions",
                ArtifactKeys.REQUIREMENT_SPEC, OutputArtifact.of("requirement-spec", spec));
    }

    /**
     * The product owner's answers, if a clarification happened. Reading them through the context records the
     * clarifications as an input, so lineage shows that the output was derived from human decisions. Shared by every
     * handler that asks the reasoning component.
     */
    static Map<String, String> clarificationAnswers(TaskContext context) {
        return context.find(ArtifactKeys.CLARIFICATIONS, Clarifications.class)
                .map(Clarifications::answers)
                .orElse(Map.of());
    }

    /** Structural contract; semantic quality (measurability, ambiguity) is judged by the requirement gate. */
    private static void validate(RequirementSpec spec) {
        if (spec.title() == null || spec.title().isBlank()) {
            throw ReasoningException.invalid("requirement spec has no title");
        }
        Set<String> ids = new HashSet<>();
        spec.acceptanceCriteria().forEach(ac -> requireUnique(ids, ac.id(), "acceptance criterion"));
        spec.assumptions().forEach(a -> requireUnique(ids, a.id(), "assumption"));
        spec.openQuestions().forEach(q -> requireUnique(ids, q.id(), "question"));
    }

    private static void requireUnique(Set<String> ids, String id, String what) {
        if (id == null || id.isBlank() || !ids.add(id)) {
            throw ReasoningException.invalid(what + " id '" + id + "' is missing or duplicated");
        }
    }
}
