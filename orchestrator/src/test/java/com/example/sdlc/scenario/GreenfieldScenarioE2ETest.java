package com.example.sdlc.scenario;

import static com.example.sdlc.scenario.ScenarioFixtures.count;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.WorkflowRun;

import tools.jackson.databind.JsonNode;

/**
 * Full greenfield run with real verification: three parallel implementation branches, a release change request
 * that revises one design decision, and selective re-planning that redoes only the work derived from it.
 */
@Tag("e2e")
class GreenfieldScenarioE2ETest {

    @TempDir
    Path runs;

    @Test
    void buildsTheFeatureInParallelAndRedoesOnlyWorkDerivedFromTheRevisedDecision() {
        ScenarioRunner.Result result = ScenarioFixtures.runner(runs)
                .start(ScenarioFixtures.scenario("greenfield"), ScenarioFixtures.options(true), ScenarioFixtures.quiet());
        WorkflowRun run = result.run();
        List<ExecutionEvent> events = result.events();

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.verdict()).isEqualTo("READY");

        // Storage, rule and documentation start together once the design is signed off.
        assertThat(events.stream().filter(e -> e.type() == EventType.TASK_STARTED)
                .mapToInt(e -> ((Number) e.data().get("inFlight")).intValue()).max().orElse(0)).isGreaterThanOrEqualTo(3);
        long apiStart = events.stream().filter(e -> e.type() == EventType.TASK_STARTED && "impl-api".equals(e.taskId()))
                .mapToLong(ExecutionEvent::seq).min().orElseThrow();
        for (String prerequisite : List.of("impl-store", "impl-policy")) {
            assertThat(events.stream().filter(e -> e.type() == EventType.TASK_SUCCEEDED && prerequisite.equals(e.taskId()))
                    .mapToLong(ExecutionEvent::seq).min().orElseThrow()).as(prerequisite).isLessThan(apiStart);
        }

        // The release reviewer sends the design back; only decision D-2 changes.
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(EventType.HUMAN_INPUT_RECEIVED);
            assertThat(String.valueOf(e.data().get("decision"))).isEqualTo("REQUEST_CHANGES");
            assertThat(e.data().get("changeTarget")).isEqualTo("design");
        });
        assertThat(count(events, EventType.TASK_STARTED, "design")).isEqualTo(2);

        // Selective re-planning: the work that consumed D-2 is rolled back and redone, the rest is preserved.
        assertThat(count(events, EventType.TASK_STARTED, "impl-policy")).isEqualTo(2);
        assertThat(count(events, EventType.TASK_STARTED, "docs")).isEqualTo(2);
        assertThat(count(events, EventType.TASK_STARTED, "tests")).isEqualTo(2);
        assertThat(count(events, EventType.TASK_STARTED, "impl-store")).isEqualTo(1);
        assertThat(count(events, EventType.TASK_STARTED, "impl-api")).isEqualTo(1);
        assertThat(count(events, EventType.CHANGESET_ROLLED_BACK, "impl-store")).isZero();
        assertThat(count(events, EventType.CHANGESET_ROLLED_BACK, "impl-api")).isZero();
        assertThat(events).filteredOn(e -> e.type() == EventType.CHANGESET_ROLLED_BACK)
                .isNotEmpty().allSatisfy(e -> assertThat(e.data().get("cause")).isEqualTo("invalidation"));

        JsonNode verification = run.artifacts().current(ArtifactKeys.VERIFICATION_REPORT).orElseThrow().content();
        assertThat(verification.path("mode").asString()).isEqualTo("BUILD");
        assertThat(verification.path("outcome").asString()).isEqualTo("PASSED");
        assertThat(result.outcome().resolve("changes.patch")).content().contains("+CREATE TABLE abuse_report (");
    }
}
