package com.example.sdlc.scenario;

import static com.example.sdlc.scenario.ScenarioFixtures.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/**
 * The ambiguous scenario's second recorded direction with real verification: resumed with
 * answers-global-ttl.yaml, the run builds a configured lifetime for every link instead of a per-link expiry,
 * passes the shortener's real test suite and is released, without repeating the discovery work done before
 * the pause.
 */
@Tag("e2e")
class AmbiguousGlobalLifetimeE2ETest {

    @TempDir
    Path runs;

    @Test
    void globalLifetimeAnswersAreBuiltVerifiedAndReleased() throws Exception {
        ScenarioRunner.Result paused = ScenarioFixtures.runner(runs)
                .start(ScenarioFixtures.scenario("ambiguous"), ScenarioFixtures.options(true), ScenarioFixtures.quiet());
        HumanRequest clarification = paused.run().pendingHumanRequests().getFirst();

        ScenarioRunner second = ScenarioFixtures.runner(runs);
        WorkflowRun stored = second.store().load(paused.run().id());
        JsonNode file = Json.YAML.readTree(Files.readString(
                ScenarioFixtures.REPOSITORY.resolve("scenarios/ambiguous/answers-global-ttl.yaml")));
        Map<String, String> answers = new LinkedHashMap<>();
        file.path("answers").properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString()));
        stored.answer(clarification.id(), HumanResponse.answer("product-owner", answers), Instant.now());
        second.store().save(stored);

        ScenarioRunner.Result result = second.resume(stored.id(), ScenarioRunner.ReviewerMode.SCRIPTED, 4, Duration.ofMinutes(5),
                ScenarioFixtures.quiet());
        WorkflowRun finished = result.run();
        List<ExecutionEvent> events = result.events();

        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.verdict()).isEqualTo("READY");
        assertThat(count(events, EventType.TASK_STARTED, "codebase-scan")).as("not repeated").isEqualTo(1);
        assertThat(count(events, EventType.TASK_STARTED, "baseline")).as("not repeated").isEqualTo(1);
        assertThat(count(events, EventType.TASK_STARTED, "requirements")).isEqualTo(2);
        assertThat(finished.plan().contains("impl-lifetime")).isTrue();
        assertThat(finished.plan().contains("impl-storage")).isFalse();

        JsonNode verification = finished.artifacts().current(ArtifactKeys.VERIFICATION_REPORT).orElseThrow().content();
        assertThat(verification.path("mode").asString()).isEqualTo("BUILD");
        assertThat(verification.path("outcome").asString()).isEqualTo("PASSED");
        assertThat(verification.path("acceptanceCoverage")).hasSize(5)
                .allSatisfy(coverage -> assertThat(coverage.path("covered").asBoolean()).isTrue());

        Workspace workspace = Workspace.open(result.directory().resolve("workspace"), result.directory().resolve("baseline"));
        assertThat(workspace.read("src/main/resources/application.yml")).get(STRING)
                .contains("link-lifetime: 365d");
        assertThat(workspace.trackedFiles()).noneMatch(path -> path.startsWith("src/main/resources/db/migration/V2"));
        assertThat(result.outcome().resolve("changes.patch")).content()
                .contains("+  link-lifetime: 365d")
                .contains("+        if (!clock.instant().isBefore(link.get().createdAt().plus(properties.linkLifetime()))) {")
                .contains("+class LinkLifetimeIntegrationTest extends AbstractIntegrationTest {")
                .doesNotContain("expires_at");
    }
}
