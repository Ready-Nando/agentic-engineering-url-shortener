package com.example.sdlc.store;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.example.sdlc.Json;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.WorkflowRun;

/**
 * File-based persistence: {@code run.json} is an atomically replaced snapshot of the run state and
 * {@code events.jsonl} an append-only audit log, one JSON event per line.
 */
public final class RunStore {

    private final Path root;

    public RunStore(Path root) {
        this.root = root;
    }

    public Path directory(String runId) {
        return root.resolve(runId);
    }

    public void save(WorkflowRun run) {
        Path directory = directory(run.id());
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "run", ".json.part");
            Files.writeString(temp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(run), StandardCharsets.UTF_8);
            Files.move(temp, directory.resolve("run.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public WorkflowRun load(String runId) {
        Path file = directory(runId).resolve("run.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no run '" + runId + "' in " + root);
        }
        try {
            return Json.MAPPER.readValue(Files.readString(file), WorkflowRun.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void append(ExecutionEvent event) {
        Path file = directory(event.runId()).resolve("events.jsonl");
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(Json.MAPPER.writeValueAsString(event));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ExecutionEvent> events(String runId) {
        Path file = directory(runId).resolve("events.jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file)) {
            return lines.filter(line -> !line.isBlank()).map(line -> Json.MAPPER.readValue(line, ExecutionEvent.class)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> runIds() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            List<String> ids = new ArrayList<>(children.filter(dir -> Files.isRegularFile(dir.resolve("run.json")))
                    .map(dir -> dir.getFileName().toString()).toList());
            ids.sort(null);
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
