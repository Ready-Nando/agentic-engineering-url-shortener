package com.example.sdlc.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.capability.StandardCapabilities;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.WorkflowRun;

class EngineeringSummaryTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final WorkflowRun run = new WorkflowRun("run-1", "brownfield", "Unique visitors", "Count visitors.", now,
            StandardCapabilities.bootstrapPlan(), Map.of("reviewer", "deferred", "verification", "build"));
    private final List<ExecutionEvent> events = new ArrayList<>();

    private void event(EventType type, String task, Integer attempt, Object... data) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < data.length; i += 2) {
            values.put((String) data[i], data[i + 1]);
        }
        events.add(new ExecutionEvent(events.size() + 1, now.plusSeconds(events.size()), run.id(), type, task, attempt, type.name(), values));
    }

    private void publish(String key, Map<String, Object> content) {
        run.artifacts().publish(key, "report", Json.tree(content), "test", 1, List.of(), now);
    }

    @Test
    void validationShowsAggregateFindingsAndComparesVerifiedWithReleasedWorkspace() {
        publish(ArtifactKeys.VERIFICATION_REPORT, Map.of("mode", "BUILD", "degraded", false, "outcome", "PASSED", "testsRun", 42,
                "failures", 0, "errors", 0, "skipped", 1, "workspaceHash", "aaaaaaaaaaaaaaaa1111",
                "acceptanceCoverage", List.of(Map.of("id", "AC-1", "tests", List.of("StatsIT"), "passingTests", List.of("StatsIT"), "covered", true))));
        publish(ArtifactKeys.SECURITY_REVIEW, Map.of("status", "CLEAN", "approvedFindings", List.of(),
                "aggregateFindings", List.of("[CC-02] schema change across two change sets")));
        publish(ArtifactKeys.RELEASE_READINESS, Map.of("ready", true, "items", List.of(), "workspaceHash", "bbbbbbbbbbbbbbbb2222"));

        String summary = EngineeringSummary.render(run, events);

        assertThat(summary).contains("| Reviewer mode | deferred |")
                .contains("42 tests, 0 failures, 0 errors, 1 skipped")
                .contains("AC-1: covered by passing tests [\"StatsIT\"]")
                .contains("Requires release attention (aggregate diff): [CC-02] schema change across two change sets")
                .contains("Workspace verified `aaaaaaaaaaaa`, assessed for release `bbbbbbbbbbbb`: **DIFFERENT**");
    }

    @Test
    void dataFlowsAreLabelledAsTypeLevelOverApproximations() {
        Map<String, Object> entry = Map.of("method", "GET", "path", "/{code}", "handlerType", "com.example.RedirectController");
        List<String> path = List.of("com.example.RedirectController", "com.example.LinkService", "com.example.LinkRepository");
        publish(ArtifactKeys.IMPACT_ANALYSIS, Map.of("seeds", List.of("LinkService"), "rationale", "redirects change",
                "analysis", Map.of("components", List.of(), "endpoints", List.of(entry), "tables", List.of("short_link"), "tests", List.of(),
                        "dataFlows", List.of(
                                Map.of("entry", entry, "path", path, "table", "short_link", "access", "READ"),
                                Map.of("entry", entry, "path", path, "table", "short_link", "access", "WRITE")))));

        String summary = EngineeringSummary.render(run, events);

        String impact = summary.substring(summary.indexOf("## 3. Codebase impact analysis"), summary.indexOf("## 4. Design decisions"));
        assertThat(impact).contains("- **Data flows** (type-level over-approximations: they follow type references, not calls, "
                        + "so a flow may list READ and WRITE for a table that a reachable repository touches; "
                        + "a GET endpoint is not necessarily writing):\n"
                        + "  - GET /{code} -> RedirectController, LinkService, LinkRepository -> `short_link` (READ)\n"
                        + "  - GET /{code} -> RedirectController, LinkService, LinkRepository -> `short_link` (WRITE)\n");
    }

    @Test
    void workspaceComparisonAndAggregateFindingsAreOptional() {
        publish(ArtifactKeys.VERIFICATION_REPORT, Map.of("mode", "STATIC", "degraded", true, "outcome", "NOT_BUILT", "testsRun", 0));
        publish(ArtifactKeys.SECURITY_REVIEW, Map.of("status", "CLEAN", "approvedFindings", List.of()));
        publish(ArtifactKeys.RELEASE_READINESS, Map.of("ready", false, "items", List.of(), "workspaceHash", "bbbb"));

        assertThat(EngineeringSummary.render(run, events)).contains("DEGRADED").doesNotContain("Workspace verified", "Requires release attention");
    }

    @Test
    void governanceAndRecoveryShowGenerationsApprovalsAndRollbackCauses() {
        event(EventType.RUN_STARTED, null, null);
        event(EventType.POLICY_EVALUATED, "impl", 1, "decision", "REQUIRE_APPROVAL", "generation", 2, "humanApproved", false, "findings", List.of());
        event(EventType.POLICY_EVALUATED, "impl", 1, "decision", "ALLOW", "generation", 2, "humanApproved", true, "findings", List.of());
        event(EventType.CHANGESET_APPLIED, "impl", 1);
        event(EventType.CHANGESET_ROLLED_BACK, "impl", 1, "cause", "rework");
        event(EventType.CHANGESET_APPLIED, "impl", 1);
        event(EventType.CHANGESET_ROLLED_BACK, "impl", 1, "cause", "safe-stop");
        event(EventType.RUN_HALTED, null, null, "verdict", "NOT_READY");

        String summary = EngineeringSummary.render(run, events);

        assertThat(summary).contains("Policy **REQUIRE_APPROVAL** for `impl` generation 2 attempt 1: []")
                .contains("Policy **ALLOW** for `impl` generation 2 attempt 1 (re-checked after human approval)")
                .contains("`CHANGESET_ROLLED_BACK` `impl` [rework]")
                .contains("Rollbacks by cause: rework 1, safe-stop 1.")
                .contains("rollback rate 100%, failure rollback rate 50%")
                .contains("| Task | Stage | Capability | Depends on | Status | Executions | Generation |");
    }
}
