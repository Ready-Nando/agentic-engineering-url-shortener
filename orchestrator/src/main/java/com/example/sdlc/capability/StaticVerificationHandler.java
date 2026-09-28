package com.example.sdlc.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.codebase.ArchitectureRules;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.codebase.LayerViolation;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.reasoning.RequirementSpec;

import tools.jackson.databind.JsonNode;

/**
 * Degraded fallback when the real build cannot run: static architecture and traceability checks only.
 * Its report is explicitly marked {@code degraded}, and release readiness refuses to declare a degraded
 * verification ready - a fallback may keep the workflow informative, but never lowers the bar silently.
 */
final class StaticVerificationHandler implements TaskHandler {

    private final CodebaseIndexer indexer;
    private final ArchitectureRules rules;
    private final boolean baseline;

    StaticVerificationHandler(CodebaseIndexer indexer, ArchitectureRules rules, boolean baseline) {
        this.indexer = indexer;
        this.rules = rules;
        this.baseline = baseline;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        context.readAll(ArtifactKeys.CHANGES_PREFIX);
        List<LayerViolation> violations = rules.check(indexer.index(context.workspace().root()));
        List<LayerViolation> preExisting = context.find(ArtifactKeys.CODEBASE_MODEL, JsonNode.class)
                .map(model -> List.of(Json.convert(model.path("baselineViolations"), LayerViolation[].class)))
                .orElse(List.of());
        List<LayerViolation> introduced = violations.stream().filter(v -> !preExisting.contains(v)).toList();

        List<VerificationReport.Coverage> coverage = new ArrayList<>();
        if (!baseline) {
            Map<String, List<String>> criteriaByTest = WorkspaceFacts.criteriaByTestType(context);
            context.find(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class).ifPresent(spec -> spec.acceptanceCriteria().forEach(ac ->
                    coverage.add(new VerificationReport.Coverage(ac.id(), criteriaByTest.entrySet().stream()
                            .filter(e -> e.getValue().contains(ac.id())).map(Map.Entry::getKey).toList(), List.of(), false))));
        }
        VerificationReport content = new VerificationReport(VerificationReport.STATIC, true, "NOT_BUILT", "", 0, 0, 0, 0, 0,
                List.of(), List.of(), coverage, List.of(), introduced, "", context.workspace().contentHash(),
                "static checks only: tests were not executed");
        String key = baseline ? ArtifactKeys.BASELINE_VERIFICATION : ArtifactKeys.VERIFICATION_REPORT;
        return TaskResult.of("DEGRADED static verification: " + introduced.size() + " new layer violations", key,
                OutputArtifact.of("verification", content));
    }
}
