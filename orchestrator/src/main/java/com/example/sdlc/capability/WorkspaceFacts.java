package com.example.sdlc.capability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.engine.TaskContext;

/** Small, shared queries over the workspace and change-set artifacts used by verification and review. */
final class WorkspaceFacts {

    static final String OPENAPI = "src/main/resources/static/openapi.yaml";
    private static final Pattern CRITERION_ID = Pattern.compile("\\bAC-\\d+\\b");

    private WorkspaceFacts() {
    }

    /** Relative paths of test sources in the workspace. */
    static List<String> testSources(Path root) {
        Path tests = root.resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(tests)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String typeNameOf(String sourcePath) {
        String withoutRoot = sourcePath.replaceFirst("^src/(main|test)/java/", "");
        return withoutRoot.substring(0, withoutRoot.length() - ".java".length()).replace('/', '.');
    }

    static String simpleNameOf(String sourcePath) {
        String file = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }

    /** Acceptance-criterion ids (AC-n) mentioned in each test source, keyed by test type name. */
    static Map<String, List<String>> criteriaByTestType(TaskContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String path : testSources(context.workspace().root())) {
            String source = context.readFile(path).orElse("");
            Matcher matcher = CRITERION_ID.matcher(source);
            List<String> ids = matcher.results().map(m -> m.group()).distinct().toList();
            if (!ids.isEmpty()) {
                result.put(typeNameOf(path), ids);
            }
        }
        return result;
    }

    /**
     * Which task's change set last wrote each path, from the {@code changes/*} artifacts, folded in the order
     * the change sets were applied (their {@code sequence}), not in artifact-key order.
     */
    static Map<String, String> ownersByPath(List<Artifact> changeArtifacts) {
        Map<String, String> owners = new LinkedHashMap<>();
        changeArtifacts.stream()
                .sorted(Comparator.comparingInt(artifact -> artifact.content().path("sequence").asInt()))
                .forEach(artifact -> artifact.content().path("files")
                        .forEach(file -> owners.put(file.path("path").asString(), artifact.producer())));
        return owners;
    }
}
