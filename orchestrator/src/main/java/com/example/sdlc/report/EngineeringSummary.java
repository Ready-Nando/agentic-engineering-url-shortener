package com.example.sdlc.report;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.example.sdlc.Json;
import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.TaskState;
import com.example.sdlc.engine.WorkflowRun;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.metrics.ReliabilityMetrics;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.reasoning.DesignProposal;
import com.example.sdlc.reasoning.RequirementSpec;

import tools.jackson.databind.JsonNode;

/**
 * Renders the final engineering summary of a run as Markdown, entirely from recorded state and events:
 * plan and rationale, artifacts, decisions, validation evidence, governance, recovery, risks, assumptions,
 * limitations and reliability metrics.
 */
public final class EngineeringSummary {

    private final WorkflowRun run;
    private final List<ExecutionEvent> events;
    private final ReliabilityMetrics metrics;
    private final StringBuilder md = new StringBuilder();

    private EngineeringSummary(WorkflowRun run, List<ExecutionEvent> events) {
        this.run = run;
        this.events = events;
        this.metrics = ReliabilityMetrics.of(events);
    }

    public static String render(WorkflowRun run, List<ExecutionEvent> events) {
        return new EngineeringSummary(run, events).build();
    }

    private String build() {
        Optional<RequirementSpec> spec = current(ArtifactKeys.REQUIREMENT_SPEC).map(a -> Json.convert(a.content(), RequirementSpec.class));
        line("# Engineering summary: " + run.title());
        line();
        line("| | |");
        line("|---|---|");
        line("| Run | `" + run.id() + "` (scenario `" + run.scenarioId() + "`) |");
        line("| Outcome | **" + run.status() + "**" + (run.verdict() == null ? "" : " - release verdict **" + run.verdict() + "**") + " |");
        line("| Reason | " + escape(run.statusReason()) + " |");
        line("| Reasoning provider | " + run.setting("provider", "?") + " |");
        line("| Verification mode | " + run.setting("verification", "?") + " |");
        line("| Reviewer mode | " + run.setting("reviewer", "?") + " |");
        line("| Plan versions | " + run.planHistory().size() + " |");
        line();

        section("1. Requirement understanding");
        line("**Raw requirement**");
        line();
        run.requirement().strip().lines().forEach(l -> line("> " + l));
        line();
        spec.ifPresent(this::requirement);
        clarifications();

        section("2. Plan and rationale");
        plan();

        current(ArtifactKeys.IMPACT_ANALYSIS).ifPresent(this::impact);

        section("4. Design decisions");
        decisions();

        section("5. Artifacts and lineage");
        artifacts();

        section("6. Validation evidence");
        validation();

        section("7. Governance: policy and human oversight");
        governance();

        section("8. Recovery and re-planning");
        recovery();

        section("9. Risks and trade-offs");
        spec.ifPresent(s -> s.risks().forEach(r -> line("- **" + r.id() + "** (" + r.severity() + "): " + r.description()
                + (r.mitigation() == null ? "" : " - _mitigation:_ " + r.mitigation()))));
        decisionArtifacts().forEach(d -> line("- Trade-off in **" + d.id() + "**: chose _" + d.choice() + "_ over "
                + d.alternatives().stream().filter(a -> !a.equals(d.choice())).toList() + " - " + d.rationale()));

        section("10. Assumptions");
        spec.ifPresent(s -> s.assumptions().forEach(a -> line("- **" + a.id() + "** (" + a.impact() + ", "
                + (a.confirmed() ? "confirmed by a human" : "unconfirmed") + "): " + a.statement())));
        spec.ifPresent(s -> s.outOfScope().forEach(o -> line("- Out of scope: " + o)));

        section("11. Limitations");
        limitations();

        section("12. Reliability metrics");
        metrics();
        return md.toString();
    }

    private void requirement(RequirementSpec spec) {
        line("**Normalised:** " + spec.title() + " (" + spec.changeType() + ")");
        line();
        line(spec.summary());
        line();
        line("| Acceptance criterion | Verification |");
        line("|---|---|");
        spec.acceptanceCriteria().forEach(ac -> line("| **" + ac.id() + "** " + escape(ac.statement()) + " | " + escape(ac.verification()) + " |"));
        line();
        if (!spec.constraints().isEmpty()) {
            line("**Constraints:** " + String.join("; ", spec.constraints()));
            line();
        }
    }

    private void clarifications() {
        List<Artifact> history = run.artifacts().history(ArtifactKeys.CLARIFICATIONS);
        if (history.isEmpty()) {
            return;
        }
        line("**Human clarifications** (the run stopped instead of guessing):");
        line();
        history.getLast().content().path("answers").properties().forEach(e ->
                line("- " + e.getKey() + " -> `" + e.getValue().asString() + "` (answered by " + history.getLast().producer() + ")"));
        line();
    }

    private void plan() {
        for (WorkflowPlan plan : run.planHistory()) {
            line("- **Plan v" + plan.version() + "** (" + plan.tasks().size() + " tasks, " + plan.trigger() + "): " + escape(plan.rationale()));
        }
        line();
        line("| Task | Stage | Capability | Depends on | Status | Executions | Generation |");
        line("|---|---|---|---|---|---|---|");
        for (TaskSpec task : run.plan().topologicalOrder()) {
            TaskState state = run.task(task.id());
            line("| `" + task.id() + "` " + escape(task.title()) + " | " + task.stage() + " | " + task.capability()
                    + (state.onFallback() ? " -> " + task.fallback() : "") + " | " + String.join(", ", task.dependsOn())
                    + " | " + state.status() + " | " + state.executions() + " | " + state.generation() + " |");
        }
        line();
        line("```mermaid");
        line("graph LR");
        for (TaskSpec task : run.plan().tasks()) {
            line("  " + node(task.id()) + "[\"" + task.id() + "<br/>" + run.task(task.id()).status() + "\"]");
            task.dependsOn().forEach(d -> line("  " + node(d) + " --> " + node(task.id())));
        }
        line("```");
    }

    private void impact(Artifact artifact) {
        section("3. Codebase impact analysis");
        JsonNode analysis = artifact.content().path("analysis");
        line("Seeds proposed by reasoning: `" + artifact.content().path("seeds") + "` - " + artifact.content().path("rationale").asString());
        line();
        line("| Impacted component | Layer | Distance | Reason |");
        line("|---|---|---|---|");
        analysis.path("components").forEach(c -> line("| `" + simple(c.path("typeName").asString()) + "` | " + c.path("layer").asString()
                + " | " + c.path("distance").asInt() + " | " + c.path("reason").asString() + " |"));
        line();
        line("- **APIs:** " + join(analysis.path("endpoints"), e -> e.path("method").asString() + " " + e.path("path").asString()));
        line("- **Tables:** " + join(analysis.path("tables"), JsonNode::asString));
        line("- **Affected tests:** " + join(analysis.path("tests"), t -> simple(t.asString())));
        line("- **Data flows:**");
        analysis.path("dataFlows").forEach(f -> line("  - " + f.path("entry").path("method").asString() + " " + f.path("entry").path("path").asString()
                + " -> " + join(f.path("path"), p -> simple(p.asString())) + " -> `" + f.path("table").asString() + "` (" + f.path("access").asString() + ")"));
    }

    private void decisions() {
        List<DesignProposal.DecisionRecord> decisions = decisionArtifacts();
        if (decisions.isEmpty()) {
            line("_No design decisions were recorded._");
            return;
        }
        for (DesignProposal.DecisionRecord decision : decisions) {
            line("- **" + decision.id() + " " + decision.title() + "** (" + decision.impact() + " impact): " + decision.choice());
            line("  - Context: " + decision.context());
            line("  - Alternatives: " + decision.alternatives());
            line("  - Rationale: " + decision.rationale());
        }
    }

    private void artifacts() {
        line("| Artifact | Producer | Status | Derived from |");
        line("|---|---|---|---|");
        for (Artifact artifact : run.artifacts().all()) {
            line("| `" + artifact.ref() + "` | " + artifact.producer() + " | " + artifact.status() + " | "
                    + artifact.inputs().stream().map(r -> "`" + r + "`").toList() + " |");
        }
        line();
        line("Inspect any node with `./sdlc lineage " + run.id() + " <artifact>`.");
    }

    private void validation() {
        long gatesPassed = events.stream().filter(e -> e.type() == EventType.GATE_PASSED).count();
        long gatesFailed = events.stream().filter(e -> e.type() == EventType.GATE_FAILED).count();
        line("- Gates evaluated: " + gatesPassed + " passed, " + gatesFailed + " failed (failures led to retry, rework, clarification or a safe stop).");
        current(ArtifactKeys.BASELINE_VERIFICATION).ifPresent(b -> line("- Baseline: " + b.content().path("mode").asString() + " "
                + b.content().path("outcome").asString() + ", " + b.content().path("testsRun").asInt() + " tests."));
        Optional<JsonNode> verification = current(ArtifactKeys.VERIFICATION_REPORT).map(Artifact::content);
        verification.ifPresent(report -> {
            line("- Final verification (" + report.path("mode").asString() + "): " + report.path("outcome").asString() + ", "
                    + report.path("testsRun").asInt() + " tests, " + report.path("failures").asInt() + " failures, "
                    + report.path("errors").asInt() + " errors, " + report.path("skipped").asInt() + " skipped"
                    + (report.path("degraded").asBoolean() ? " - **DEGRADED, tests not executed**" : "") + ".");
            report.path("failedTests").forEach(t -> line("  - failed `" + simple(t.path("className").asString("")) + "."
                    + t.path("testName").asString("") + "`: " + escape(t.path("message").asString(""))));
            report.path("acceptanceCoverage").forEach(c -> line("  - " + c.path("id").asString() + ": "
                    + (c.path("covered").asBoolean() ? "covered by passing tests " + c.path("passingTests")
                    : "NOT covered; referenced by " + c.path("tests"))));
        });
        current(ArtifactKeys.SECURITY_REVIEW).ifPresent(s -> {
            line("- Security/compliance review: " + s.content().path("status").asString()
                    + ", approved findings " + s.content().path("approvedFindings"));
            s.content().path("aggregateFindings").forEach(f -> line("  - Requires release attention (aggregate diff): " + f.asString()));
        });
        current(ArtifactKeys.API_COMPATIBILITY).ifPresent(a -> line("- API review: " + a.content().path("status").asString()
                + ", added " + a.content().path("addedOperations") + ", breaking " + a.content().path("breakingChanges")));
        latest(ArtifactKeys.RELEASE_READINESS).ifPresent(r -> {
            line("- Release readiness" + (r.isCurrent() ? ":" : " (" + r.status() + " - this assessment blocked the release):"));
            r.content().path("items").forEach(i -> line("  - [" + (i.path("passed").asBoolean() ? "x" : " ") + "] "
                    + i.path("title").asString() + " - " + i.path("evidence").asString()));
            String verified = verification.map(v -> v.path("workspaceHash").asString(null)).orElse(null);
            String released = r.content().path("workspaceHash").asString(null);
            if (verified != null && released != null) {
                line("  - Workspace verified `" + abbreviate(verified) + "`, assessed for release `" + abbreviate(released) + "`: "
                        + (verified.equals(released) ? "identical" : "**DIFFERENT**"));
            }
        });
    }

    private void governance() {
        events.stream().filter(e -> e.type() == EventType.POLICY_EVALUATED).forEach(e ->
                line("- Policy **" + e.text("decision") + "** for `" + e.taskId() + "`"
                        + (e.text("generation") == null ? "" : " generation " + e.text("generation")) + " attempt " + e.attempt()
                        + (Boolean.TRUE.equals(e.data().get("humanApproved")) ? " (re-checked after human approval)" : "")
                        + ": " + e.data().get("findings")));
        for (HumanRequest request : run.humanRequests()) {
            line("- Human checkpoint `" + request.id() + "` (" + request.kind() + ", " + request.taskId() + "): " + request.title()
                    + " -> " + (request.response() == null ? request.status() : request.response().decision() + " by "
                    + request.response().reviewer() + (request.response().comment().isBlank() ? "" : " - \"" + request.response().comment() + "\"")));
        }
    }

    private void recovery() {
        List<ExecutionEvent> recoveryEvents = events.stream().filter(e -> switch (e.type()) {
            case ATTEMPT_FAILED, RETRY_SCHEDULED, FALLBACK_ACTIVATED, REWORK_REQUESTED, CHANGESET_ROLLED_BACK,
                 TASK_INVALIDATED, HUMAN_INPUT_CANCELLED, PLAN_REVISED, ARTIFACT_UNCHANGED, SAFE_STOP_INITIATED,
                 WORKSPACE_RESTORED -> true;
            default -> false;
        }).toList();
        if (recoveryEvents.isEmpty()) {
            line("_No failures, retries or re-planning were needed._");
            return;
        }
        recoveryEvents.forEach(e -> line("- `" + e.type() + "` " + (e.taskId() == null ? "" : "`" + e.taskId() + "` ")
                + (e.type() == EventType.CHANGESET_ROLLED_BACK ? "[" + cause(e) + "] " : "") + escape(e.message())));
        if (metrics.rollbacks() > 0) {
            line();
            line("Rollbacks by cause: " + byCause() + ".");
        }
    }

    private void limitations() {
        line("- Reasoning came from `" + run.setting("provider", "?") + "`. With the recorded provider the proposals are replayed recordings; "
                + "all orchestration, gates, policy, approvals, workspace changes and verification are executed for real.");
        if (!"build".equals(run.setting("verification", "build"))) {
            line("- Real builds were disabled; verification was static only, so the release cannot be declared ready.");
        }
        run.allTasks().stream().filter(TaskState::onFallback)
                .forEach(t -> line("- `" + t.taskId() + "` completed through its fallback capability (degraded)."));
        line("- The outcome is a reviewable patch against the target module; nothing was merged or deployed.");
    }

    private void metrics() {
        line("| Metric | Value |");
        line("|---|---|");
        line("| Outcome | " + metrics.outcome() + " |");
        line("| Task success rate | " + percent(metrics.taskSuccessRate()) + " (" + metrics.tasksSucceeded() + " tasks succeeded, "
                + metrics.tasksFailed() + " failed; " + metrics.taskExecutionsSucceeded() + " successful executions) |");
        line("| Attempts / retries | " + metrics.attempts() + " / " + metrics.retries() + " (retry rate " + percent(metrics.retryRate()) + ") |");
        line("| Fallbacks / reworks | " + metrics.fallbacks() + " / " + metrics.reworks() + " |");
        line("| Change sets applied / rolled back | " + metrics.changeSetsApplied() + " / " + metrics.rollbacks() + " (rollback rate "
                + percent(metrics.rollbackRate()) + ", failure rollback rate " + percent(metrics.failureRollbackRate()) + ") |");
        line("| Rollbacks by cause | " + (metrics.rollbacks() == 0 ? "-" : byCause()) + " |");
        line("| Invalidations / plan revisions | " + metrics.invalidations() + " / " + metrics.planRevisions() + " |");
        line("| Policy evaluations / denials | " + metrics.policyEvaluations() + " / " + metrics.policyDenials() + " |");
        line("| Human checkpoints / decisions | " + metrics.humanRequests() + " / " + metrics.humanDecisions() + " |");
        line("| MTTR | " + format(metrics.meanTimeToRecovery()) + " over " + metrics.recoveries() + " recoveries (" + metrics.unrecovered() + " unrecovered) |");
        line("| End-to-end latency | " + format(metrics.endToEnd()) + " (active " + format(metrics.activeTime()) + ", human wait " + format(metrics.humanWait()) + ") |");
        line("| Max concurrent tasks | " + metrics.maxConcurrency() + " |");
    }

    private String byCause() {
        return String.join(", ", metrics.rollbacksByCause().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList());
    }

    private static String cause(ExecutionEvent rollback) {
        return Objects.requireNonNullElse(rollback.text("cause"), ReliabilityMetrics.UNSPECIFIED_CAUSE);
    }

    private List<DesignProposal.DecisionRecord> decisionArtifacts() {
        return run.artifacts().currentArtifacts().stream()
                .filter(a -> a.key().startsWith(ArtifactKeys.DECISION_PREFIX))
                .map(a -> Json.convert(a.content(), DesignProposal.DecisionRecord.class))
                .toList();
    }

    private Optional<Artifact> current(String key) {
        return run.artifacts().current(key);
    }

    private Optional<Artifact> latest(String key) {
        List<Artifact> history = run.artifacts().history(key);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    private void section(String title) {
        line();
        line("## " + title);
        line();
    }

    private void line(String text) {
        md.append(text == null ? "" : text).append('\n');
    }

    private void line() {
        md.append('\n');
    }

    private static String node(String id) {
        return id.replace('-', '_');
    }

    private static String simple(String typeName) {
        return typeName.substring(typeName.lastIndexOf('.') + 1);
    }

    private static String join(JsonNode array, java.util.function.Function<JsonNode, String> mapper) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(n -> values.add(mapper.apply(n)));
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private static String abbreviate(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }

    public static String format(Duration duration) {
        long millis = duration.toMillis();
        return millis < 1000 ? millis + " ms" : String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }
}
