package com.example.sdlc.capability;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.reasoning.DesignProposal;
import com.example.sdlc.reasoning.ReasoningException;
import com.example.sdlc.reasoning.ReasoningProvider;
import com.example.sdlc.reasoning.ReasoningRequests.DesignRequest;
import com.example.sdlc.reasoning.RequirementSpec;

import tools.jackson.databind.JsonNode;

/**
 * Produces the design as several independent artifacts (overview, API contract, data model and one artifact
 * per decision) so downstream work depends only on the parts it actually reads - which is what lets a design
 * revision invalidate the minimum amount of work.
 */
final class DesignHandler implements TaskHandler {

    private final ReasoningProvider reasoning;

    DesignHandler(ReasoningProvider reasoning) {
        this.reasoning = reasoning;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        RequirementSpec spec = context.read(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class);
        JsonNode impact = context.find(ArtifactKeys.IMPACT_ANALYSIS, JsonNode.class).orElse(null);
        DesignProposal design = reasoning.proposeDesign(new DesignRequest(context.task().id(), context.invocation(), spec,
                impact, context.feedback()));
        if (design.decisions().isEmpty()) {
            throw ReasoningException.invalid("design contains no decisions");
        }
        Map<String, OutputArtifact> outputs = new LinkedHashMap<>();
        outputs.put(ArtifactKeys.DESIGN_OVERVIEW, OutputArtifact.of("design-overview",
                Map.of("summary", design.summary(), "components", design.components())));
        outputs.put(ArtifactKeys.API_CONTRACT, OutputArtifact.of("api-contract", Map.of("operations", design.apiOperations())));
        outputs.put(ArtifactKeys.DATA_MODEL, OutputArtifact.of("data-model", Map.of("tables", design.dataModel())));
        for (DesignProposal.DecisionRecord decision : design.decisions()) {
            outputs.put(ArtifactKeys.DECISION_PREFIX + decision.id(), OutputArtifact.of("decision", decision));
        }
        return TaskResult.of(design.decisions().size() + " decisions, " + design.apiOperations().size()
                + " API operations, " + design.dataModel().size() + " data model changes", outputs);
    }
}
