package com.example.sdlc.reasoning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
 * {@code expect} preconditions (which clarification answers it was recorded for), checked for every kind of
 * request. File content too large to inline is referenced as a {@code contentFile} relative to the recordings
 * directory, and must lie in its {@code files/} directory.
 *
 * <p>Different human answers can lead to different recorded work. A variant
 * {@code recordings/variants/<name>/} declares in {@code variant.yaml} the option answers it was recorded for
 * ({@code when: {Q-1: global-ttl}}); a request whose answers equal all of them is served only from that
 * directory (and its own {@code files/}). Answers matching no variant use the default recordings; answers
 * matching several are refused. Selection is deliberately nothing more than this exact match.
 *
 * <p>Recordings are treated exactly like live model output: they pass through contract validation, gates,
 * policy and approvals. When no recording exists the provider fails with REASONING_UNAVAILABLE rather than
 * improvising, so the run stops safely instead of inventing work.
 */
public final class RecordedReasoningProvider implements ReasoningProvider {

    private final Path scenario;
    private final Path recordings;

    /** Where one request's recordings come from; {@code variant} is null for the default recordings. */
    private record Source(String variant, Path recordings, Path contentFiles) {
        String describe() {
            return variant == null ? "" : " in recorded variant '" + variant + "'";
        }
    }

    public RecordedReasoningProvider(Path scenarioDirectory) {
        this.scenario = scenarioDirectory;
        this.recordings = scenarioDirectory.resolve("recordings");
    }

    @Override
    public String name() {
        return "recorded:" + scenario.getFileName();
    }

    @Override
    public RequirementSpec analyzeRequirement(RequirementRequest request) {
        return convert(load(source(request.answers()), request.taskId(), request.invocation(), request.answers()),
                RequirementSpec.class, request.taskId());
    }

    @Override
    public ImpactSeeds proposeImpactSeeds(ImpactRequest request) {
        return convert(load(source(request.answers()), request.taskId(), request.invocation(), request.answers()),
                ImpactSeeds.class, request.taskId());
    }

    @Override
    public PlanProposal proposePlan(PlanRequest request) {
        return convert(load(source(request.answers()), request.taskId(), request.invocation(), request.answers()),
                PlanProposal.class, request.taskId());
    }

    @Override
    public DesignProposal proposeDesign(DesignRequest request) {
        return convert(load(source(request.answers()), request.taskId(), request.invocation(), request.answers()),
                DesignProposal.class, request.taskId());
    }

    @Override
    public ChangeProposal proposeChanges(ChangeRequest request) {
        Source source = source(request.answers());
        JsonNode recording = load(source, request.taskId(), request.invocation(), request.answers());
        JsonNode changes = recording.path("response").path("changes");
        Iterator<JsonNode> iterator = changes.iterator();
        while (iterator.hasNext()) {
            JsonNode change = iterator.next();
            if (change.hasNonNull("contentFile") && change instanceof ObjectNode object) {
                object.put("content", readText(contentFile(source, change.get("contentFile").asString(), request.taskId())));
                object.remove("contentFile");
            }
        }
        return convert(recording, ChangeProposal.class, request.taskId());
    }

    /** The one variant whose {@code when} answers all equal the request's, or the default recordings if none does. */
    private Source source(Map<String, String> answers) {
        Path variants = recordings.resolve("variants");
        // A linked variant tree could silently disappear (and send its answers to the default recordings), so refuse it.
        if (Files.isSymbolicLink(variants)) {
            throw ReasoningException.unavailable("recorded variants must not be a symbolic link (" + scenario.relativize(variants) + ")");
        }
        if (!Files.isDirectory(variants, LinkOption.NOFOLLOW_LINKS)) {
            return new Source(null, recordings, recordings.resolve("files"));
        }
        List<String> matching = new ArrayList<>();
        try (Stream<Path> entries = Files.list(variants)) {
            for (Path variant : entries.sorted().toList()) {
                if (Files.isSymbolicLink(variant)) {
                    throw ReasoningException.unavailable("recorded variant '" + variant.getFileName()
                            + "' must not be a symbolic link (" + scenario.relativize(variant) + ")");
                }
                if (Files.isDirectory(variant, LinkOption.NOFOLLOW_LINKS) && matches(variant, answers)) {
                    matching.add(variant.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (matching.size() > 1) {
            throw ReasoningException.unavailable("the clarification answers match several recorded variants " + matching
                    + "; each supported set of answers must select exactly one");
        }
        if (matching.isEmpty()) {
            return new Source(null, recordings, recordings.resolve("files"));
        }
        Path variant = variants.resolve(matching.getFirst());
        return new Source(matching.getFirst(), variant, variant.resolve("files"));
    }

    private boolean matches(Path variant, Map<String, String> answers) {
        Path descriptor = variant.resolve("variant.yaml");
        JsonNode when = recordingExists(descriptor, variant) ? Json.YAML.readTree(readText(descriptor)).path("when") : null;
        // A variant without answers would match every request, including runs that were never clarified.
        if (when == null || !when.isObject() || when.isEmpty()) {
            throw ReasoningException.unavailable("recorded variant '" + variant.getFileName() + "' declares no 'when' answers ("
                    + scenario.relativize(descriptor) + ")");
        }
        for (Map.Entry<String, JsonNode> expected : when.properties()) {
            JsonNode option = expected.getValue();
            // A list, object or empty value here is a typo that would otherwise turn the variant into dead recordings.
            if (!option.isValueNode() || option.isNull() || option.asString().isBlank()) {
                throw ReasoningException.unavailable("recorded variant '" + variant.getFileName() + "' expects a single option for "
                        + expected.getKey() + " (" + scenario.relativize(descriptor) + ")");
            }
        }
        for (Map.Entry<String, JsonNode> expected : when.properties()) {
            if (!expected.getValue().asString().equals(answers.get(expected.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private JsonNode load(Source source, String taskId, int invocation, Map<String, String> answers) {
        Path file = source.recordings().resolve(taskId + "." + invocation + ".yaml");
        if (!recordingExists(file, source.recordings())) {
            throw ReasoningException.unavailable("no recorded reasoning for task '" + taskId + "' invocation " + invocation
                    + source.describe() + " (" + scenario.relativize(file) + ")");
        }
        JsonNode recording = Json.YAML.readTree(readText(file));
        JsonNode expectedAnswers = recording.path("expect").path("answers");
        if (expectedAnswers.isObject()) {
            for (Map.Entry<String, JsonNode> expected : expectedAnswers.properties()) {
                String actual = answers.get(expected.getKey());
                if (!expected.getValue().asString().equals(actual)) {
                    throw ReasoningException.unavailable("recording for " + taskId + " #" + invocation + source.describe()
                            + " assumes answer " + expected.getKey() + "=" + expected.getValue().asString() + " but got " + actual
                            + "; the offline provider only covers the recorded answers (see the scenario README)");
                }
            }
        }
        return recording;
    }

    /**
     * Whether {@code file} exists. One that does must be a regular file whose real path lies inside
     * {@code directory}, the recordings in use: a link must not replay reasoning recorded somewhere else, such
     * as outside the scenario, or in the default recordings for answers that selected a variant.
     */
    private boolean recordingExists(Path file, Path directory) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (Files.isRegularFile(file) && file.toRealPath().startsWith(directory.toRealPath())) {
                return true;
            }
        } catch (IOException e) {
            // A dangling link: reported below, like a link to anywhere else.
        }
        throw ReasoningException.unavailable(scenario.relativize(file) + " is not a regular file inside "
                + scenario.relativize(directory) + "/; recorded reasoning is only replayed from there");
    }

    /** Resolves a recording's content file, which must lie in its source's {@code files/}, also after following links. */
    private Path contentFile(Source source, String reference, String taskId) {
        Path contentFiles = source.contentFiles();
        try {
            Path relative = Path.of(reference);
            Path file = source.recordings().resolve(relative).normalize();
            if (!relative.isAbsolute() && relative.getRoot() == null && !hasParentSegment(relative)
                    && file.startsWith(contentFiles.normalize()) && Files.isRegularFile(file)
                    && file.toRealPath().startsWith(contentFiles.toRealPath())) {
                return file;
            }
        } catch (InvalidPathException | IOException e) {
            // Reported below, like any other reference outside the files directory.
        }
        throw ReasoningException.invalid("recording for " + taskId + " references content file '" + reference
                + "', which is not a file inside " + scenario.relativize(contentFiles) + "/");
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
