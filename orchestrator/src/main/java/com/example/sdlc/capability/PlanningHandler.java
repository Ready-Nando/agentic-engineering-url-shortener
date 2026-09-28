package com.example.sdlc.capability;

import java.util.List;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.plan.PlanProposal;
import com.example.sdlc.reasoning.ReasoningException;
import com.example.sdlc.reasoning.ReasoningProvider;
import com.example.sdlc.reasoning.ReasoningRequests.PlanRequest;
import com.example.sdlc.reasoning.RequirementSpec;

import tools.jackson.databind.JsonNode;

/**
 * Asks the reasoning component to decompose the work. The proposal is only data: the plan-valid gate and the
 * engine's plan validator decide whether (and in what normalised form) it becomes the executable graph.
 */
final class PlanningHandler implements TaskHandler {

    private final ReasoningProvider reasoning;
    private final List<String> plannableCapabilities;

    PlanningHandler(ReasoningProvider reasoning, List<String> plannableCapabilities) {
        this.reasoning = reasoning;
        this.plannableCapabilities = List.copyOf(plannableCapabilities);
    }

    @Override
    public TaskResult execute(TaskContext context) {
        RequirementSpec spec = context.read(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class);
        JsonNode impact = context.find(ArtifactKeys.IMPACT_ANALYSIS, JsonNode.class).orElse(null);
        PlanProposal proposal = reasoning.proposePlan(new PlanRequest(context.task().id(), context.invocation(), spec,
                impact, plannableCapabilities, context.feedback()));
        if (proposal.tasks().isEmpty()) {
            throw ReasoningException.invalid("plan proposal contains no tasks");
        }
        return TaskResult.of("proposed " + proposal.tasks().size() + " tasks",
                ArtifactKeys.PLAN_PROPOSAL, OutputArtifact.of("plan-proposal", proposal));
    }
}
