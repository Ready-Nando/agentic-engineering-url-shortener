package com.example.sdlc.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.artifact.ArtifactStore;

class LineageTest {

    @Test
    void answersProvenanceQuestionsInBothDirections() {
        ArtifactStore store = new ArtifactStore();
        Instant now = Instant.now();
        ArtifactRef spec = store.publish("requirement/spec", "spec", Json.tree(Map.of("v", 1)), "requirements", 1, List.of(), now).artifact().ref();
        ArtifactRef answers = store.publish("requirement/clarifications", "c", Json.tree(Map.of("Q-1", "410")), "human:dana", 0, List.of(), now).artifact().ref();
        ArtifactRef decision = store.publish("decision/D-1", "decision", Json.tree(Map.of("choice", "410")), "design", 1, List.of(spec, answers), now).artifact().ref();
        store.publish("changes/impl", "change-set", Json.tree(Map.of("files", List.of())), "impl", 2, List.of(decision), now);
        store.publish("decision/D-1", "decision", Json.tree(Map.of("choice", "404")), "design", 2, List.of(spec, answers), now);

        Lineage lineage = new Lineage(store);
        Artifact change = lineage.resolve("changes/impl").orElseThrow();

        assertThat(lineage.upstream(change)).anyMatch(l -> l.contains("decision/D-1@v1") && l.contains("SUPERSEDED"))
                .anyMatch(l -> l.contains("requirement/clarifications@v1") && l.contains("human:dana"));
        assertThat(lineage.downstream(lineage.resolve("requirement/spec@v1").orElseThrow()))
                .anyMatch(l -> l.contains("decision/D-1@v1")).anyMatch(l -> l.contains("changes/impl@v1"));
        assertThat(lineage.resolve("decision/D-1").orElseThrow().version()).isEqualTo(2);
    }
}
