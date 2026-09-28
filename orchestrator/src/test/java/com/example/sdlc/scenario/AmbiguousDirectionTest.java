package com.example.sdlc.scenario;

import static com.example.sdlc.scenario.ScenarioFixtures.count;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.engine.AttemptRecord;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.FailureKind;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.TaskStatus;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.TaskSpec;

import tools.jackson.databind.JsonNode;

/**
 * The product owner's answers to the ambiguous scenario's clarification decide what gets built. The real
 * scenario runs with static verification (no Maven, so this is fast): the same paused run is resumed with the
 * two recorded answer sets and with an unsupported one. Each supported answer set leads to a different
 * specification, plan and design, all derived from the human answers; answers nobody recorded stop the run
 * before planning instead of replaying work made for other decisions.
 */
class AmbiguousDirectionTest {

    @TempDir
    Path runs;

    @Test
    void perLinkAnswersLeadToPerLinkStorageAndApiChanges() throws Exception {
        WorkflowRun run = clarifyAndResume(answersFile("answers.yaml")).run();

        assertThat(spec(run).path("title").asString()).isEqualTo("Optional per-link expiry for short links");
        assertThat(taskIds(run)).contains("impl-storage", "impl-lifecycle").doesNotContain("impl-lifetime");
        assertThat(decision(run, "D-1").path("choice").asString()).startsWith("Nullable expires_at column");
        assertThat(decision(run, "D-2").path("choice").asString()).startsWith("410");
        assertThat(run.artifacts().history(ArtifactKeys.DATA_MODEL).getFirst().content().path("tables"))
                .extracting(table -> table.path("table").asString()).containsExactly("short_link");
        assertThat(designSignOff(run).details()).contains("data model ALTER short_link").noneMatch(d -> d.startsWith("HIGH"));
        assertThat(changedPaths(run, "impl-storage")).contains("src/main/resources/db/migration/V2__add_expires_at_to_short_link.sql");
    }

    @Test
    void globalLifetimeAnswersLeadToAConfiguredLifetimeAndADifferentPlan() throws Exception {
        ScenarioRunner.Result result = clarifyAndResume(answersFile("answers-global-ttl.yaml"));
        WorkflowRun run = result.run();

        JsonNode spec = spec(run);
        assertThat(spec.path("title").asString()).isEqualTo("Configured lifetime for every short link");
        assertThat(spec.path("acceptanceCriteria").get(1).path("statement").asString()).contains("404", "link-not-found");
        assertThat(spec.path("assumptions").get(0).path("confirmed").asBoolean()).as("A-1 confirmed by a human").isTrue();

        // One configuration-and-service task instead of the per-link direction's storage and lifecycle tasks.
        assertThat(taskIds(run)).contains("impl-lifetime").doesNotContain("impl-storage", "impl-lifecycle");
        TaskSpec verify = run.plan().task("verify");
        assertThat(verify.verifies()).containsExactlyInAnyOrder("impl-lifetime", "docs", "tests");
        assertThat(run.plan().task("impl-lifetime").scope()).contains("src/main/resources/application.yml");

        JsonNode retroactive = decision(run, "D-2");
        assertThat(retroactive.path("title").asString()).isEqualTo("Retroactive expiry of existing links");
        assertThat(retroactive.path("impact").asString()).isEqualTo("HIGH");
        assertThat(decision(run, "D-3").path("choice").asString()).startsWith("404");
        assertThat(run.artifacts().history(ArtifactKeys.DATA_MODEL).getFirst().content().path("tables").isEmpty())
                .as("no schema change").isTrue();
        // Without a data model change, the sign-off is required by the high-impact decision alone.
        assertThat(designSignOff(run).details()).containsExactly(
                "HIGH impact decision D-2: Retroactive expiry of existing links -> " + retroactive.path("choice").asString());
        assertThat(changedPaths(run, "impl-lifetime")).containsExactly(
                "src/main/java/com/example/shortener/config/ShortenerProperties.java",
                "src/main/java/com/example/shortener/link/LinkService.java",
                "src/main/resources/application.yml");

        // The human answers are an input of the specification and of every later reasoning step.
        Artifact clarifications = run.artifacts().current(ArtifactKeys.CLARIFICATIONS).orElseThrow();
        assertThat(clarifications.producer()).isEqualTo("human:product-owner");
        assertThat(clarifications.content().path("answers").path("Q-1").asString()).isEqualTo("global-ttl");
        for (String key : List.of(ArtifactKeys.REQUIREMENT_SPEC, ArtifactKeys.IMPACT_ANALYSIS, ArtifactKeys.PLAN_PROPOSAL,
                ArtifactKeys.DESIGN_OVERVIEW, "decision/D-2", ArtifactKeys.changesOf("impl-lifetime"),
                ArtifactKeys.changesOf("docs"), ArtifactKeys.changesOf("tests"))) {
            assertThat(run.artifacts().history(key).getFirst().inputs()).as(key).extracting(ArtifactRef::key)
                    .contains(ArtifactKeys.CLARIFICATIONS);
        }
        assertThat(count(result.events(), EventType.TASK_STARTED, "codebase-scan")).as("not repeated").isEqualTo(1);
    }

    @Test
    void answersWithoutRecordingsStopBeforePlanning() throws Exception {
        Map<String, String> unsupported = new LinkedHashMap<>(answersFile("answers-global-ttl.yaml"));
        unsupported.put("Q-1", "both");
        ScenarioRunner.Result result = clarifyAndResume(unsupported);
        WorkflowRun run = result.run();

        assertThat(run.status()).isEqualTo(RunStatus.HALTED);
        assertThat(run.task("requirements").status()).isEqualTo(TaskStatus.FAILED);
        AttemptRecord attempt = run.task("requirements").history().getLast();
        assertThat(attempt.failureKind()).isEqualTo(FailureKind.REASONING_UNAVAILABLE);
        assertThat(attempt.message()).contains("Q-1=per-link", "got both");
        assertThat(run.task("planning").status()).isNotEqualTo(TaskStatus.SUCCEEDED);
        assertThat(count(result.events(), EventType.TASK_STARTED, "planning")).isZero();
        assertThat(run.artifacts().current(ArtifactKeys.REQUIREMENT_SPEC)).isEmpty();
        assertThat(run.artifacts().history(ArtifactKeys.PLAN_PROPOSAL)).isEmpty();
        assertThat(run.appliedChanges()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private ScenarioRunner.Result clarifyAndResume(Map<String, String> answers) {
        ScenarioRunner.Result paused = ScenarioFixtures.runner(runs)
                .start(ScenarioFixtures.scenario("ambiguous"), ScenarioFixtures.options(false), ScenarioFixtures.quiet());
        assertThat(paused.run().status()).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest clarification = paused.run().pendingHumanRequests().getFirst();
        assertThat(clarification.kind()).isEqualTo(HumanRequest.Kind.CLARIFICATION);

        ScenarioRunner runner = ScenarioFixtures.runner(runs);
        WorkflowRun stored = runner.store().load(paused.run().id());
        stored.answer(clarification.id(), HumanResponse.answer("product-owner", answers), Instant.now());
        runner.store().save(stored);
        return runner.resume(stored.id(), ScenarioRunner.ReviewerMode.SCRIPTED, 4, Duration.ofMinutes(5), ScenarioFixtures.quiet());
    }

    private static Map<String, String> answersFile(String name) throws Exception {
        JsonNode file = Json.YAML.readTree(Files.readString(ScenarioFixtures.REPOSITORY.resolve("scenarios/ambiguous").resolve(name)));
        Map<String, String> answers = new LinkedHashMap<>();
        file.path("answers").properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString()));
        return answers;
    }

    /** The accepted specification, which must have been derived from the human answers. */
    private static JsonNode spec(WorkflowRun run) {
        Artifact spec = run.artifacts().history(ArtifactKeys.REQUIREMENT_SPEC).getLast();
        assertThat(spec.inputs()).extracting(ArtifactRef::key).contains(ArtifactKeys.CLARIFICATIONS);
        return spec.content();
    }

    private static List<String> taskIds(WorkflowRun run) {
        return run.plan().tasks().stream().map(TaskSpec::id).toList();
    }

    private static JsonNode decision(WorkflowRun run, String id) {
        return run.artifacts().history(ArtifactKeys.DECISION_PREFIX + id).getFirst().content();
    }

    private static HumanRequest designSignOff(WorkflowRun run) {
        return run.humanRequests().stream()
                .filter(r -> r.kind() == HumanRequest.Kind.GATE_APPROVAL && r.taskId().equals("design"))
                .findFirst().orElseThrow();
    }

    private static List<String> changedPaths(WorkflowRun run, String taskId) {
        JsonNode changes = run.artifacts().history(ArtifactKeys.changesOf(taskId)).getFirst().content();
        return changes.path("files").valueStream().map(f -> f.path("path").asString()).toList();
    }
}
