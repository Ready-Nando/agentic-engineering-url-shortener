package com.example.sdlc.scenario;

import static com.example.sdlc.scenario.ScenarioFixtures.count;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;
import com.example.sdlc.engine.TaskStatus;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/**
 * The brownfield scenario with real builds disabled: fast and fully deterministic. Governance still runs for
 * real (plan validation, policy, approvals), verification falls back to degraded static checks, and release
 * readiness must refuse to call the outcome ready - after which every applied change is compensated.
 */
class DegradedVerificationScenarioTest {

    @TempDir
    Path runs;

    @Test
    void degradedVerificationCanNeverBeReleasedAndAllChangesAreCompensated() {
        ScenarioRunner.Result result = ScenarioFixtures.runner(runs)
                .start(ScenarioFixtures.scenario("brownfield"), ScenarioFixtures.options(false), ScenarioFixtures.quiet());
        WorkflowRun run = result.run();
        List<ExecutionEvent> events = result.events();

        assertThat(run.status()).isEqualTo(RunStatus.HALTED);
        assertThat(run.verdict()).isEqualTo("NOT_READY");
        assertThat(run.task("release").status()).isEqualTo(TaskStatus.FAILED);

        assertThat(run.task("baseline").onFallback()).isTrue();
        assertThat(run.task("verify").onFallback()).isTrue();
        assertThat(count(events, EventType.FALLBACK_ACTIVATED, null)).isEqualTo(2);
        assertThat(count(events, EventType.RETRY_SCHEDULED, "verify")).as("unavailable tools are not retried").isZero();

        assertThat(run.artifacts().current(ArtifactKeys.RELEASE_READINESS)).as("a rejected assessment is never current").isEmpty();
        com.example.sdlc.artifact.Artifact assessment = run.artifacts().history(ArtifactKeys.RELEASE_READINESS).getLast();
        assertThat(assessment.status()).isEqualTo(com.example.sdlc.artifact.Artifact.Status.REJECTED);
        JsonNode readiness = assessment.content();
        assertThat(readiness.path("ready").asBoolean()).isFalse();
        assertThat(failedItems(readiness)).contains("baseline-verified", "tests-executed-and-passing", "acceptance-criteria-covered");

        assertThat(run.planHistory()).hasSize(2);
        assertThat(count(events, EventType.PLAN_REVISED, null)).isEqualTo(1);
        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(EventType.POLICY_EVALUATED);
            assertThat(e.taskId()).isEqualTo("impl-capture");
            assertThat(e.text("decision")).isEqualTo("DENY");
            assertThat(e.text("rules")).contains("CMP-01").contains("GOV-01");
        });
        assertThat(run.humanRequests()).extracting(HumanRequest::taskId, r -> r.response().decision())
                .contains(org.assertj.core.groups.Tuple.tuple("design", HumanResponse.Decision.APPROVE),
                        org.assertj.core.groups.Tuple.tuple("impl-capture", HumanResponse.Decision.APPROVE));

        Workspace workspace = Workspace.open(result.directory().resolve("workspace"), result.directory().resolve("baseline"));
        assertThat(workspace.contentHash()).isEqualTo(workspace.baselineHash());
        assertThat(run.appliedChanges()).isEmpty();
        assertThat(run.artifacts().current(ArtifactKeys.ABANDONED_CHANGES)).isPresent();
        assertThat(result.outcome().resolve("abandoned-changes.patch")).exists();
        assertThat(result.outcome().resolve("changes.patch")).doesNotExist();
        assertThat(result.outcome().resolve("ENGINEERING_SUMMARY.md")).content().contains("NOT_READY", "DEGRADED");
    }

    private static List<String> failedItems(JsonNode readiness) {
        List<String> failed = new java.util.ArrayList<>();
        readiness.path("items").forEach(item -> {
            if (!item.path("passed").asBoolean()) {
                failed.add(item.path("id").asString());
            }
        });
        return failed;
    }
}
