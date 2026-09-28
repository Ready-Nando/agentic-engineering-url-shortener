package com.example.sdlc.capability;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.reasoning.ChangeProposal;
import com.example.sdlc.reasoning.ReasoningException;
import com.example.sdlc.reasoning.ReasoningProvider;
import com.example.sdlc.reasoning.ReasoningRequests.ChangeRequest;
import com.example.sdlc.reasoning.RequirementSpec;
import com.example.sdlc.workspace.ChangeSet;
import com.example.sdlc.workspace.FileChange;

import tools.jackson.databind.JsonNode;

/**
 * Implementation, test authoring and documentation all work the same way: gather the relevant context, ask
 * for a proposal, and return it as a change set. Applying it is the engine's job (after policy and, when
 * required, human approval). Every file read is recorded so lineage covers code the proposal built on.
 */
final class ChangeProposalHandler implements TaskHandler {

    private static final int MAX_CONTEXT_FILES = 60;

    private final ReasoningProvider reasoning;

    ChangeProposalHandler(ReasoningProvider reasoning) {
        this.reasoning = reasoning;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        RequirementSpec spec = context.read(ArtifactKeys.REQUIREMENT_SPEC, RequirementSpec.class);
        Map<String, JsonNode> design = new TreeMap<>();
        List<String> inputs = context.task().inputs().isEmpty() ? List.of("design/", ArtifactKeys.DECISION_PREFIX) : context.task().inputs();
        for (String input : inputs) {
            if (input.endsWith("/")) {
                context.readAll(input).forEach(artifact -> design.put(artifact.key(), artifact.content()));
            } else {
                context.find(input, JsonNode.class).ifPresent(content -> design.put(input, content));
            }
        }
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> baseHashes = new LinkedHashMap<>();
        for (String path : filesInScope(context)) {
            context.readFile(path).ifPresent(content -> {
                files.put(path, content);
                baseHashes.put(path, Json.sha256(content));
            });
        }
        ChangeProposal proposal = reasoning.proposeChanges(new ChangeRequest(context.task().id(), context.invocation(),
                context.task().goal(), context.task().scope(), spec, RequirementAnalysisHandler.clarificationAnswers(context),
                design, files, context.feedback()));
        validate(proposal);
        // Files the proposal edits or deletes but that were not in the context window are read now, so they too get
        // an optimistic-concurrency base hash and a lineage edge to whichever change set last wrote them.
        for (FileChange change : proposal.changes()) {
            if (change.op() != FileChange.Op.CREATE && !baseHashes.containsKey(change.path())) {
                context.readFile(change.path()).ifPresent(content -> baseHashes.put(change.path(), Json.sha256(content)));
            }
        }
        ChangeSet changes = new ChangeSet(proposal.summary(), proposal.declaredRisk(), proposal.changes(), Map.of())
                .withBaseHashes(onlyTouched(baseHashes, proposal));
        return TaskResult.withChanges(proposal.summary(), changes, Map.of(
                "rationale/" + context.task().id(), OutputArtifact.of("rationale", Map.of(
                        "summary", proposal.summary(),
                        "rationale", proposal.rationale() == null ? "" : proposal.rationale(),
                        "declaredRisk", proposal.declaredRisk() == null ? "UNSPECIFIED" : proposal.declaredRisk()))));
    }

    private static List<String> filesInScope(TaskContext context) {
        List<PathMatcher> matchers = context.task().scope().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        return context.workspace().trackedFiles().stream()
                .filter(p -> matchers.stream().anyMatch(m -> m.matches(Path.of(p))))
                .limit(MAX_CONTEXT_FILES)
                .toList();
    }

    private static Map<String, String> onlyTouched(Map<String, String> baseHashes, ChangeProposal proposal) {
        Map<String, String> touched = new LinkedHashMap<>();
        for (FileChange change : proposal.changes()) {
            if (baseHashes.containsKey(change.path())) {
                touched.put(change.path(), baseHashes.get(change.path()));
            }
        }
        return touched;
    }

    private static void validate(ChangeProposal proposal) {
        if (proposal.changes().isEmpty()) {
            throw ReasoningException.invalid("change proposal is empty");
        }
        for (FileChange change : proposal.changes()) {
            if (change.op() == null || change.path() == null || change.path().isBlank()) {
                throw ReasoningException.invalid("change without operation or path");
            }
            if (change.op() == FileChange.Op.EDIT && (change.find() == null || change.replace() == null)) {
                throw ReasoningException.invalid("edit of " + change.path() + " needs both 'find' and 'replace'");
            }
            if (change.op() == FileChange.Op.CREATE && change.content() == null) {
                throw ReasoningException.invalid("creation of " + change.path() + " has no content");
            }
        }
    }
}
