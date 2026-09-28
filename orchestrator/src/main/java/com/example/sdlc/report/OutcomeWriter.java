package com.example.sdlc.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.metrics.ReliabilityMetrics;
import com.example.sdlc.workspace.Workspace;

/**
 * Writes the reviewable outcome of a run: the patch, the engineering summary, reliability metrics and a
 * human-readable export of every artifact version.
 */
public final class OutcomeWriter {

    private OutcomeWriter() {
    }

    public static Path write(WorkflowRun run, Workspace workspace, List<ExecutionEvent> events, Path runDirectory) {
        Path outcome = runDirectory.resolve("outcome");
        try {
            Files.createDirectories(outcome);
            Files.deleteIfExists(outcome.resolve("changes.patch"));
            if (run.status() == RunStatus.COMPLETED && !workspace.changedPaths().isEmpty()) {
                Files.writeString(outcome.resolve("changes.patch"), workspace.unifiedDiff(run.setting("patchPrefix", "")));
            }
            run.artifacts().current(ArtifactKeys.ABANDONED_CHANGES).ifPresent(abandoned ->
                    write(outcome.resolve("abandoned-changes.patch"), abandoned.content().path("diff").asString()));
            Files.writeString(outcome.resolve("ENGINEERING_SUMMARY.md"), EngineeringSummary.render(run, events));
            Files.writeString(outcome.resolve("metrics.json"),
                    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ReliabilityMetrics.of(events)));
            Path artifacts = runDirectory.resolve("artifacts");
            Files.createDirectories(artifacts);
            for (Artifact artifact : run.artifacts().all()) {
                String name = artifact.key().replace('/', '_') + ".v" + artifact.version() + ".json";
                Files.writeString(artifacts.resolve(name), Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(artifact));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return outcome;
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
