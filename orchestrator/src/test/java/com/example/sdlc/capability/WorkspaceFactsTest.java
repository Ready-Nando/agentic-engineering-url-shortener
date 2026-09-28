package com.example.sdlc.capability;

import static com.example.sdlc.capability.CapabilityFixtures.changes;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.sdlc.artifact.Artifact;

class WorkspaceFactsTest {

    @Test
    void lastWriterOfAPathFollowsApplicationSequenceNotArtifactKeyOrder() {
        // Key order (changes/a < changes/b) and string order of the sequences ("12" < "9") both disagree with the
        // order the change sets were applied in: b (sequence 9) first, then a (sequence 12).
        List<Artifact> byKey = List.of(
                changes("a", 12, "src/main/java/demo/Shared.java", "src/main/java/demo/OnlyA.java"),
                changes("b", 9, "src/main/java/demo/Shared.java", "src/main/java/demo/OnlyB.java"));

        Map<String, String> owners = WorkspaceFacts.ownersByPath(byKey);

        assertThat(owners).containsEntry("src/main/java/demo/Shared.java", "a")
                .containsEntry("src/main/java/demo/OnlyA.java", "a")
                .containsEntry("src/main/java/demo/OnlyB.java", "b")
                .hasSize(3);
        assertThat(WorkspaceFacts.ownersByPath(byKey.reversed())).isEqualTo(owners);
    }
}
