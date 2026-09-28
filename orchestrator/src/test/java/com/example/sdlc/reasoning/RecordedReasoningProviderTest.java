package com.example.sdlc.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.TaskFailure;
import com.example.sdlc.reasoning.ReasoningRequests.ChangeRequest;
import com.example.sdlc.reasoning.ReasoningRequests.RequirementRequest;
import com.example.sdlc.workspace.FileChange;

class RecordedReasoningProviderTest {

    @TempDir
    Path scenario;

    private RecordedReasoningProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        Path recordings = scenario.resolve("recordings");
        Files.createDirectories(recordings.resolve("files"));
        Files.writeString(recordings.resolve("requirements.1.yaml"), """
                response:
                  title: Expiry
                  acceptanceCriteria:
                    - {id: AC-1, statement: Links expire, verification: test}
                """);
        Files.writeString(recordings.resolve("requirements.2.yaml"), """
                expect:
                  answers: {Q-1: per-link}
                response:
                  title: Per-link expiry
                """);
        Files.writeString(recordings.resolve("impl.1.yaml"), """
                response:
                  summary: add file
                  declaredRisk: LOW
                  changes:
                    - {op: CREATE, path: src/A.java, contentFile: files/A.java}
                """);
        Files.writeString(recordings.resolve("files/A.java"), "class A {}\n");
        Files.writeString(recordings.resolve("broken.1.yaml"), """
                response:
                  acceptanceCriteria: "not a list"
                """);
        provider = new RecordedReasoningProvider(scenario);
    }

    private static ChangeRequest change(String taskId) {
        return new ChangeRequest(taskId, 1, "", List.of(), null, Map.of(), Map.of(), List.of());
    }

    private void recordChangeWithContentFile(String taskId, String contentFile) throws Exception {
        Files.writeString(scenario.resolve("recordings").resolve(taskId + ".1.yaml"), """
                response:
                  summary: add file
                  changes:
                    - {op: CREATE, path: src/B.java, contentFile: '%s'}
                """.formatted(contentFile));
    }

    private static RequirementRequest requirement(String taskId, int invocation, Map<String, String> answers) {
        return new RequirementRequest(taskId, invocation, "text", answers, List.of());
    }

    @Test
    void replaysTheRecordingForTheTaskInvocation() {
        assertThat(provider.analyzeRequirement(requirement("requirements", 1, Map.of())).title()).isEqualTo("Expiry");
    }

    @Test
    void resolvesContentFilesForCreatedFiles() {
        ChangeProposal proposal = provider.proposeChanges(change("impl"));

        assertThat(proposal.changes()).singleElement().satisfies(change -> {
            assertThat(change.op()).isEqualTo(FileChange.Op.CREATE);
            assertThat(change.content()).isEqualTo("class A {}\n");
        });
    }

    @Test
    void refusesToImproviseWhenNoRecordingExists() {
        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 3, Map.of())))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE));
    }

    @Test
    void refusesRecordingsMadeForDifferentHumanAnswers() {
        assertThat(provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "per-link"))).title()).isEqualTo("Per-link expiry");
        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("Q-1=per-link").contains("global");
                });
    }

    @Test
    void outputViolatingTheContractIsInvalid() {
        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("broken", 1, Map.of())))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_OUTPUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "files/../../outside.txt", "files/../impl.1.yaml", "impl.1.yaml", "files/missing.java"})
    void contentFilesMustLieInsideTheFilesDirectory(String reference) throws Exception {
        Files.writeString(scenario.resolve("outside.txt"), "not a recording\n");
        recordChangeWithContentFile("escape", reference);

        assertThatThrownBy(() -> provider.proposeChanges(change("escape")))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_OUTPUT))
                .hasMessageContaining("not a file inside recordings/files/");
    }

    @Test
    void absoluteContentFilePathsAreRejected() throws Exception {
        Path outside = Files.writeString(scenario.resolve("outside.txt"), "not a recording\n");
        recordChangeWithContentFile("absolute", outside.toAbsolutePath().toString());

        assertThatThrownBy(() -> provider.proposeChanges(change("absolute")))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_OUTPUT))
                .hasMessageContaining("not a file inside recordings/files/");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void contentFilesCannotEscapeThroughSymbolicLinks() throws Exception {
        Path outside = Files.writeString(scenario.resolve("outside.txt"), "not a recording\n");
        Files.createSymbolicLink(scenario.resolve("recordings/files/Linked.java"), outside);
        recordChangeWithContentFile("linked", "files/Linked.java");

        assertThatThrownBy(() -> provider.proposeChanges(change("linked")))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_OUTPUT))
                .hasMessageContaining("not a file inside recordings/files/");
    }
}
