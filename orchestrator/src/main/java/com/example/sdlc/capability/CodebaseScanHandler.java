package com.example.sdlc.capability;

import java.util.List;
import java.util.Map;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.codebase.ArchitectureRules;
import com.example.sdlc.codebase.CodebaseIndexer;
import com.example.sdlc.codebase.CodebaseModel;
import com.example.sdlc.codebase.LayerViolation;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;

/** Deterministic static model of the target codebase, including pre-existing architecture violations. */
final class CodebaseScanHandler implements TaskHandler {

    private final CodebaseIndexer indexer;
    private final ArchitectureRules rules;

    CodebaseScanHandler(CodebaseIndexer indexer, ArchitectureRules rules) {
        this.indexer = indexer;
        this.rules = rules;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        CodebaseModel model = indexer.index(context.workspace().baselineRoot());
        List<LayerViolation> violations = rules.check(model);
        return TaskResult.of(model.units().size() + " types, " + model.endpoints().size() + " endpoints, "
                        + model.tables().size() + " tables, " + violations.size() + " layer violations",
                ArtifactKeys.CODEBASE_MODEL, OutputArtifact.of("codebase-model", Map.of(
                        "summary", model.summary(), "model", model, "baselineViolations", violations)));
    }
}
