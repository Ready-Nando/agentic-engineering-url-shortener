package com.example.sdlc.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.TaskFailure;
import com.example.sdlc.reasoning.ReasoningRequests.ChangeRequest;
import com.example.sdlc.reasoning.ReasoningRequests.DesignRequest;
import com.example.sdlc.reasoning.ReasoningRequests.ImpactRequest;
import com.example.sdlc.reasoning.ReasoningRequests.PlanRequest;
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
        return change(taskId, Map.of());
    }

    private static ChangeRequest change(String taskId, Map<String, String> answers) {
        return new ChangeRequest(taskId, 1, "", List.of(), null, answers, Map.of(), Map.of(), List.of());
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

    // ---------------------------------------------------------------- answer-keyed variants

    private void recordVariant(String name, String when) throws Exception {
        Path variant = scenario.resolve("recordings/variants").resolve(name);
        Files.createDirectories(variant.resolve("files"));
        Files.writeString(variant.resolve("variant.yaml"), "when: " + when + "\n");
    }

    private void record(String relativePath, String content) throws Exception {
        Path file = scenario.resolve("recordings").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void answersMatchingAVariantAreServedOnlyFromThatVariant() throws Exception {
        recordVariant("global", "{Q-1: global-ttl, Q-2: '404'}");
        record("variants/global/requirements.2.yaml", """
                response:
                  title: Global lifetime
                """);
        Map<String, String> global = Map.of("Q-1", "global-ttl", "Q-2", "404", "AC-3", "free text is not matched");

        assertThat(provider.analyzeRequirement(requirement("requirements", 2, global)).title()).isEqualTo("Global lifetime");
        // The default recording for the same invocation still serves the answers it was recorded for.
        assertThat(provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "per-link"))).title())
                .isEqualTo("Per-link expiry");
        // Every listed answer must match: Q-2 differs, so the variant does not apply.
        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global-ttl", "Q-2", "410"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("Q-1=per-link");
    }

    @Test
    void aVariantNeverFallsBackToTheDefaultRecordings() throws Exception {
        recordVariant("global", "{Q-1: global-ttl}");

        // impl.1.yaml exists in the default recordings, but it was recorded for other decisions.
        assertThatThrownBy(() -> provider.proposeChanges(change("impl", Map.of("Q-1", "global-ttl"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("variant 'global'")
                .hasMessageContaining("recordings/variants/global/impl.1.yaml");
    }

    @Test
    void answersMatchingSeveralVariantsAreRefused() throws Exception {
        recordVariant("global", "{Q-1: global-ttl}");
        recordVariant("global-404", "{Q-1: global-ttl, Q-2: '404'}");
        record("variants/global/requirements.2.yaml", "response: {title: A}\n");
        record("variants/global-404/requirements.2.yaml", "response: {title: B}\n");

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global-ttl", "Q-2", "404"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("several recorded variants [global, global-404]");
        assertThat(provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global-ttl", "Q-2", "410"))).title())
                .isEqualTo("A");
    }

    @Test
    void aVariantWithoutAnswersIsRefusedInsteadOfMatchingEveryRequest() throws Exception {
        recordVariant("anything", "{}");

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 1, Map.of())))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("declares no 'when' answers");
    }

    @Test
    void aMalformedVariantAnswerIsRefusedInsteadOfNeverMatching() throws Exception {
        recordVariant("global", "{Q-1: [global-ttl]}");

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global-ttl"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("expects a single option for Q-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{Q-1: }", "{Q-1: null}", "{Q-1: ''}", "{Q-1: '  '}"})
    void anEmptyVariantAnswerIsRefusedInsteadOfNeverMatching(String when) throws Exception {
        recordVariant("global", when);

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 1, Map.of())))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("expects a single option for Q-1");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void recordingsLinkedFromOutsideTheRecordingsInUseAreRefused() throws Exception {
        Path elsewhere = Files.createDirectories(scenario.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("requirements.yaml"), "response: {title: Recorded elsewhere}\n");
        Files.createSymbolicLink(scenario.resolve("recordings/linked.1.yaml"), elsewhere.resolve("requirements.yaml"));
        // Answers that select a variant must not be served a default recording through a link either.
        recordVariant("global", "{Q-1: global-ttl}");
        Files.createSymbolicLink(scenario.resolve("recordings/variants/global/requirements.1.yaml"),
                scenario.resolve("recordings/requirements.1.yaml"));
        // A link to another recording of the same directory stays inside it.
        Files.createSymbolicLink(scenario.resolve("recordings/alias.1.yaml"), Path.of("requirements.1.yaml"));

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("linked", 1, Map.of())))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("recordings/linked.1.yaml is not a regular file inside recordings/");
        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 1, Map.of("Q-1", "global-ttl"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("recordings/variants/global/requirements.1.yaml is not a regular file inside recordings/variants/global/");
        assertThat(provider.analyzeRequirement(requirement("alias", 1, Map.of())).title()).isEqualTo("Expiry");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void aVariantDescriptorLinkedFromOutsideTheVariantIsRefused() throws Exception {
        Path elsewhere = Files.createDirectories(scenario.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("variant.yaml"), "when: {Q-1: global-ttl}\n");
        Path variant = Files.createDirectories(scenario.resolve("recordings/variants/global"));
        Files.createSymbolicLink(variant.resolve("variant.yaml"), elsewhere.resolve("variant.yaml"));
        record("variants/global/requirements.2.yaml", "response: {title: Global lifetime}\n");

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global-ttl"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("recordings/variants/global/variant.yaml is not a regular file inside recordings/variants/global/");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void aLinkedVariantIsRefusedInsteadOfFallingBackToTheDefaultRecordings() throws Exception {
        Path elsewhere = scenario.resolve("elsewhere");
        Files.createDirectories(elsewhere);
        Files.writeString(elsewhere.resolve("variant.yaml"), "when: {Q-1: global-ttl}\n");
        Files.createDirectories(scenario.resolve("recordings/variants"));
        Files.createSymbolicLink(scenario.resolve("recordings/variants/global"), elsewhere);

        assertThatThrownBy(() -> provider.analyzeRequirement(requirement("requirements", 2, Map.of("Q-1", "global-ttl"))))
                .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                .hasMessageContaining("must not be a symbolic link");
    }

    @Test
    void expectedAnswersAreCheckedForEveryKindOfRequest() throws Exception {
        String expect = """
                expect:
                  answers: {Q-1: per-link}
                """;
        record("impact.1.yaml", expect + "response: {seeds: [ShortLink], rationale: r}\n");
        record("planning.1.yaml", expect + "response: {rationale: r, tasks: []}\n");
        record("design.1.yaml", expect + "response: {summary: s}\n");
        record("tests.1.yaml", expect + """
                response:
                  summary: s
                  changes:
                    - {op: CREATE, path: src/A.java, contentFile: files/A.java}
                """);
        Map<String, String> other = Map.of("Q-1", "both");

        List<ThrowingCallable> calls = List.of(
                () -> provider.proposeImpactSeeds(new ImpactRequest("impact", 1, null, other, "", List.of())),
                () -> provider.proposePlan(new PlanRequest("planning", 1, null, other, null, List.of(), List.of())),
                () -> provider.proposeDesign(new DesignRequest("design", 1, null, other, null, List.of())),
                () -> provider.proposeChanges(change("tests", other)));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE))
                    .hasMessageContaining("Q-1=per-link").hasMessageContaining("got both");
        }
        Map<String, String> recorded = Map.of("Q-1", "per-link");
        assertThat(provider.proposeImpactSeeds(new ImpactRequest("impact", 1, null, recorded, "", List.of())).seeds()).containsExactly("ShortLink");
        assertThat(provider.proposeDesign(new DesignRequest("design", 1, null, recorded, null, List.of())).summary()).isEqualTo("s");
        assertThat(provider.proposeChanges(change("tests", recorded)).changes()).hasSize(1);
    }

    @Test
    void variantContentFilesResolveInsideTheVariantsOwnFilesDirectory() throws Exception {
        recordVariant("global", "{Q-1: global-ttl}");
        record("variants/global/files/A.java", "class GlobalA {}\n");
        String changeWith = """
                response:
                  summary: add file
                  changes:
                    - {op: CREATE, path: src/A.java, contentFile: '%s'}
                """;
        record("variants/global/own.1.yaml", changeWith.formatted("files/A.java"));
        record("variants/global/default.1.yaml", changeWith.formatted("../../files/A.java"));
        record("variants/global/sibling.1.yaml", changeWith.formatted("files/../../../files/A.java"));
        Map<String, String> global = Map.of("Q-1", "global-ttl");

        assertThat(provider.proposeChanges(change("own", global)).changes().getFirst().content()).isEqualTo("class GlobalA {}\n");
        for (String escaping : List.of("default", "sibling")) {
            assertThatThrownBy(() -> provider.proposeChanges(change(escaping, global)))
                    .isInstanceOfSatisfying(TaskFailure.class, e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_OUTPUT))
                    .hasMessageContaining("not a file inside recordings/variants/global/files/");
        }
    }
}
