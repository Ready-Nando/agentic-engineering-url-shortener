package com.example.sdlc.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.example.sdlc.Json;

/**
 * A scenario definition from {@code scenarios/<id>/scenario.yaml}: the raw requirement, the target module and
 * the scripted reviewer's decisions. Its {@code recordings/} directory holds the recorded reasoning.
 */
public record Scenario(
        String id,
        String kind,
        String title,
        String description,
        String requirement,
        String targetModule,
        ScriptedReviewer.Script reviewer,
        Path directory) {

    public static Scenario load(Path directory) {
        Path file = directory.resolve("scenario.yaml");
        try {
            Definition definition = Json.YAML.readValue(Files.readString(file), Definition.class);
            return new Scenario(definition.id(), definition.kind(), definition.title(), definition.description(),
                    definition.requirement(), definition.targetModule() == null ? "shortener" : definition.targetModule(),
                    definition.reviewer() == null ? new ScriptedReviewer.Script("scenario-reviewer", List.of()) : definition.reviewer(),
                    directory.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public static List<Scenario> discover(Path scenariosRoot) {
        try (Stream<Path> children = Files.list(scenariosRoot)) {
            return children.filter(dir -> Files.isRegularFile(dir.resolve("scenario.yaml")))
                    .sorted()
                    .map(Scenario::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list scenarios in " + scenariosRoot, e);
        }
    }

    private record Definition(String id, String kind, String title, String description, String requirement,
                              String targetModule, ScriptedReviewer.Script reviewer) {
    }
}
