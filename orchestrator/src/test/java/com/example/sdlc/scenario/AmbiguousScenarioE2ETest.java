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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.artifact.ArtifactRef;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.TaskStatus;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;

import tools.jackson.databind.JsonNode;

/**
 * Full ambiguous run with real verification, across two runner instances as across two processes: the run
 * pauses on a clarification, the product owner's answers are recorded, and the resumed run finishes without
 * repeating the discovery work that was already done.
 */
@Tag("e2e")
class AmbiguousScenarioE2ETest {

    @TempDir
    Path runs;

    @Test
    void pausesForClarificationAndResumesWithoutRedoingFinishedWork() throws Exception {
        ScenarioRunner.Result paused = ScenarioFixtures.runner(runs)
                .start(ScenarioFixtures.scenario("ambiguous"), ScenarioFixtures.options(true), ScenarioFixtures.quiet());
        WorkflowRun run = paused.run();

        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_HUMAN);
        HumanRequest clarification = run.pendingHumanRequests().getFirst();
        assertThat(clarification.kind()).isEqualTo(HumanRequest.Kind.CLARIFICATION);
        // Q-1 and Q-2 come from the analysis; the gate adds the unconfirmed HIGH assumption and the vague criterion.
        assertThat(clarification.questions()).extracting(HumanRequest.Question::id).containsExactly("Q-1", "Q-2", "A-1", "AC-3");
        assertThat(run.task("codebase-scan").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(run.task("baseline").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(run.task("planning").status()).isEqualTo(TaskStatus.PENDING);

        ScenarioRunner second = ScenarioFixtures.runner(runs);
        WorkflowRun stored = second.store().load(run.id());
        JsonNode file = Json.YAML.readTree(Files.readString(ScenarioFixtures.REPOSITORY.resolve("scenarios/ambiguous/answers.yaml")));
        Map<String, String> answers = new LinkedHashMap<>();
        file.path("answers").properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString()));
        stored.answer(clarification.id(), HumanResponse.answer("product-owner", answers), Instant.now());
        second.store().save(stored);

        ScenarioRunner.Result result = second.resume(run.id(), ScenarioRunner.ReviewerMode.SCRIPTED, 4, Duration.ofMinutes(5),
                ScenarioFixtures.quiet());
        WorkflowRun finished = result.run();
        List<ExecutionEvent> events = result.events();

        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.verdict()).isEqualTo("READY");
        assertThat(count(events, EventType.RUN_PAUSED, null)).isEqualTo(1);
        assertThat(count(events, EventType.RUN_RESUMED, null)).isEqualTo(1);
        assertThat(count(events, EventType.TASK_STARTED, "codebase-scan")).as("not repeated").isEqualTo(1);
        assertThat(count(events, EventType.TASK_STARTED, "baseline")).as("not repeated").isEqualTo(1);
        assertThat(count(events, EventType.TASK_STARTED, "requirements")).isEqualTo(2);

        // The analysis that stopped at its gate was never published; the accepted specification is derived from
        // the recorded answers, which a human produced.
        Artifact clarifications = finished.artifacts().current(ArtifactKeys.CLARIFICATIONS).orElseThrow();
        assertThat(clarifications.producer()).isEqualTo("human:product-owner");
        Artifact spec = finished.artifacts().current(ArtifactKeys.REQUIREMENT_SPEC).orElseThrow();
        assertThat(finished.artifacts().history(ArtifactKeys.REQUIREMENT_SPEC)).hasSize(1);
        assertThat(spec.inputs()).extracting(ArtifactRef::key).contains(ArtifactKeys.CLARIFICATIONS);

        JsonNode verification = finished.artifacts().current(ArtifactKeys.VERIFICATION_REPORT).orElseThrow().content();
        assertThat(verification.path("mode").asString()).isEqualTo("BUILD");
        assertThat(verification.path("outcome").asString()).isEqualTo("PASSED");
        assertThat(result.outcome().resolve("changes.patch")).content()
                .contains("+ALTER TABLE short_link ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE NULL;");
    }
}
