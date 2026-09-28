package com.example.sdlc.reasoning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import com.example.sdlc.Json;
import com.example.sdlc.plan.PlanProposal;
import com.example.sdlc.reasoning.ReasoningRequests.ChangeRequest;
import com.example.sdlc.reasoning.ReasoningRequests.DesignRequest;
import com.example.sdlc.reasoning.ReasoningRequests.ImpactRequest;
import com.example.sdlc.reasoning.ReasoningRequests.PlanRequest;
import com.example.sdlc.reasoning.ReasoningRequests.RequirementRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic provider that replays recorded reasoning outputs ("cassettes") from a scenario directory:
 * {@code recordings/<taskId>.<invocation>.yaml}. Each file has a {@code response} and optional
 * {@code expect} preconditions (e.g. which clarification answers it was recorded for). File content too large
 * to inline is referenced as a {@code contentFile} relative to {@code recordings/}, and must lie in
 * {@code recordings/files/}.
 *
 * <p>Recordings are treated exactly like live model output: they pass through contract validation, gates,
 * policy and approvals. When no recording exists the provider fails with REASONING_UNAVAILABLE rather than
 * improvising, so the run stops safely instead of inventing work.
 */
public final class RecordedReasoningProvider implements ReasoningProvider {

    private final Path recordings;
    private final Path contentFiles;

    public RecordedReasoningProvider(Path scenarioDirectory) {
        this.recordings = scenarioDirectory.resolve("recordings");
        this.contentFiles = recordings.resolve("files");
    }

    @Override
    public String name() {
        return "recorded:" + recordings.getParent().getFileName();
    }

    @Override
    public RequirementSpec analyzeRequirement(RequirementRequest request) {
        JsonNode recording = load(request.taskId(), request.invocation());
        JsonNode expectedAnswers = recording.path("expect").path("answers");
        if (expectedAnswers.isObject()) {
            for (Map.Entry<String, JsonNode> expected : expectedAnswers.properties()) {
                String actual = request.answers().get(expected.getKey());
                if (!expected.getValue().asString().equals(actual)) {
                    throw ReasoningException.unavailable("recorded analysis for " + request.taskId() + " #" + request.invocation()
                            + " assumes answer " + expected.getKey() + "=" + expected.getValue().asString() + " but got " + actual
                            + "; the offline provider only covers the recorded answers (see the scenario README)");
                }
            }
        }
        return convert(recording, RequirementSpec.class, request.taskId());
    }

    @Override
    public ImpactSeeds proposeImpactSeeds(ImpactRequest request) {
        return convert(load(request.taskId(), request.invocation()), ImpactSeeds.class, request.taskId());
    }

    @Override
    public PlanProposal proposePlan(PlanRequest request) {
        return convert(load(request.taskId(), request.invocation()), PlanProposal.class, request.taskId());
    }

    @Override
    public DesignProposal proposeDesign(DesignRequest request) {
        return convert(load(request.taskId(), request.invocation()), DesignProposal.class, request.taskId());
    }

    @Override
    public ChangeProposal proposeChanges(ChangeRequest request) {
        JsonNode recording = load(request.taskId(), request.invocation());
        JsonNode changes = recording.path("response").path("changes");
        Iterator<JsonNode> iterator = changes.iterator();
        while (iterator.hasNext()) {
            JsonNode change = iterator.next();
            if (change.hasNonNull("contentFile") && change instanceof ObjectNode object) {
                object.put("content", readText(contentFile(change.get("contentFile").asString(), request.taskId())));
                object.remove("contentFile");
            }
        }
        return convert(recording, ChangeProposal.class, request.taskId());
    }

    private JsonNode load(String taskId, int invocation) {
        Path file = recordings.resolve(taskId + "." + invocation + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw ReasoningException.unavailable("no recorded reasoning for task '" + taskId + "' invocation " + invocation
                    + " (" + recordings.relativize(file) + ")");
        }
        return Json.YAML.readTree(readText(file));
    }

    /** Resolves a recording's content file, which must lie in {@code recordings/files/}, also after following links. */
    private Path contentFile(String reference, String taskId) {
        try {
            Path relative = Path.of(reference);
            Path file = recordings.resolve(relative).normalize();
            if (!relative.isAbsolute() && relative.getRoot() == null && !hasParentSegment(relative)
                    && file.startsWith(contentFiles.normalize()) && Files.isRegularFile(file)
                    && file.toRealPath().startsWith(contentFiles.toRealPath())) {
                return file;
            }
        } catch (InvalidPathException | IOException e) {
            // Reported below, like any other reference outside recordings/files/.
        }
        throw ReasoningException.invalid("recording for " + taskId + " references content file '" + reference
                + "', which is not a file inside recordings/files/");
    }

    private static boolean hasParentSegment(Path path) {
        for (Path name : path) {
            if (name.toString().equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static <T> T convert(JsonNode recording, Class<T> type, String taskId) {
        JsonNode response = recording.path("response");
        if (response.isMissingNode() || response.isNull()) {
            throw ReasoningException.invalid("recording for " + taskId + " has no 'response'");
        }
        try {
            return Json.convert(response, type);
        } catch (RuntimeException e) {
            throw ReasoningException.invalid("recording for " + taskId + " does not match the " + type.getSimpleName()
                    + " contract: " + e.getMessage());
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
