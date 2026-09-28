package com.example.sdlc.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.codebase.CodebaseModel;
import com.example.sdlc.codebase.ImpactAnalysis;
import com.example.sdlc.codebase.ImpactAnalyzer;
import com.example.sdlc.codebase.ImpactedComponent;
import com.example.sdlc.codebase.SourceUnit;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.reasoning.ImpactSeeds;
import com.example.sdlc.reasoning.ReasoningProvider;
import com.example.sdlc.reasoning.ReasoningRequests.ImpactRequest;
import com.example.sdlc.reasoning.RequirementSpec;

import tools.jackson.databind.JsonNode;

/**
 * Probabilistic edge, deterministic centre: the reasoning component names where the change starts; the blast
 * radius (dependents, endpoints, tables, data flows, tests) is computed from the real code.
 */
final class ImpactAnalysisHandler implements TaskHandler {

    private final ReasoningProvider reasoning;
    private final ImpactAnalyzer analyzer;

    ImpactAnalysisHandler(ReasoningProvider reasoning, ImpactAnalyzer analyzer) {
        this.reasoning = reasoning;
        this.analyzer = analyzer;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        RequirementSpec spec = context.read(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class);
        JsonNode codebase = context.read(ArtifactKeys.CODEBASE_MODEL, JsonNode.class);
        CodebaseModel model = Json.convert(codebase.path("model"), CodebaseModel.class);
        ImpactSeeds seeds = reasoning.proposeImpactSeeds(new ImpactRequest(context.task().id(), context.invocation(), spec,
                RequirementAnalysisHandler.clarificationAnswers(context), codebase.path("summary").asString(), context.feedback()));
        ImpactAnalysis analysis = analyzer.analyze(model, seeds.seeds());

        TreeSet<String> anticipated = new TreeSet<>();
        analysis.components().stream().map(ImpactedComponent::path).filter(Objects::nonNull).forEach(anticipated::add);
        analysis.tests().stream()
                .map(test -> model.unit(test).map(SourceUnit::path).orElse(null))
                .filter(Objects::nonNull)
                .forEach(anticipated::add);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("seeds", seeds.seeds());
        content.put("rationale", seeds.rationale());
        content.put("analysis", analysis);
        content.put("anticipatedPaths", List.copyOf(anticipated));
        return TaskResult.of(analysis.components().size() + " impacted components, " + analysis.endpoints().size()
                        + " endpoints, " + analysis.tables().size() + " tables, " + analysis.dataFlows().size() + " data flows, "
                        + analysis.tests().size() + " affected tests",
                ArtifactKeys.IMPACT_ANALYSIS, OutputArtifact.of("impact-analysis", content));
    }
}
