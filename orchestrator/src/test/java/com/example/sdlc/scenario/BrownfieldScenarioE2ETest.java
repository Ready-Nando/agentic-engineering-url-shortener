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
 * Full brownfield run with real verification: the shortener's own test suite runs (through its Maven
 * wrapper) in the isolated workspace, first on the baseline and then on the changed code. Slow (about
 * 20 s), so it is only part of the {@code e2e} profile.
 */
@Tag("e2e")
class BrownfieldScenarioE2ETest {

    @TempDir
    Path runs;

    @Test
    void enhancesAnalyticsUnderGovernanceAndReworksOnlyTheDefectiveChange() {
        ScenarioRunner.Result result = ScenarioFixtures.runner(runs)
                .start(ScenarioFixtures.scenario("brownfield"), ScenarioFixtures.options(true), ScenarioFixtures.quiet());
        WorkflowRun run = result.run();
        List<ExecutionEvent> events = result.events();

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.verdict()).isEqualTo("READY");

        JsonNode impact = run.artifacts().current(ArtifactKeys.IMPACT_ANALYSIS).orElseThrow().content();
        assertThat(impact.path("analysis").path("tests").toString()).contains("RedirectIntegrationTest", "StatsApiIntegrationTest");
        assertThat(impact.path("anticipatedPaths").toString()).contains("RedirectController.java");

        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(EventType.REWORK_REQUESTED);
            assertThat(e.data().get("targets")).isEqualTo(List.of("impl-stats"));
        });
        assertThat(count(events, EventType.TASK_STARTED, "impl-stats")).isEqualTo(2);
        assertThat(count(events, EventType.TASK_STARTED, "impl-capture")).as("not a suspect, not reworked").isEqualTo(2);
        assertThat(count(events, EventType.TASK_STARTED, "tests")).isEqualTo(1);
        assertThat(count(events, EventType.CHANGESET_ROLLED_BACK, "impl-stats")).isEqualTo(1);

        JsonNode verification = run.artifacts().current(ArtifactKeys.VERIFICATION_REPORT).orElseThrow().content();
        assertThat(verification.path("mode").asString()).isEqualTo("BUILD");
        assertThat(verification.path("outcome").asString()).isEqualTo("PASSED");
        assertThat(verification.path("testsRun").asInt()).isGreaterThan(140);
        assertThat(result.outcome().resolve("changes.patch")).content().contains("+ALTER TABLE click_event ADD COLUMN visitor_hash CHAR(64) NULL;");
    }
}
