package com.example.sdlc.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;

import tools.jackson.databind.JsonNode;

/**
 * Release readiness checklist computed only from evidence (artifacts and the workspace itself). The
 * {@code readiness-checklist} gate turns any failed item into NOT READY; human sign-off is a separate gate.
 * Workspace integrity is not re-checked here: the {@code workspace-integrity} entry gate has just enforced it.
 */
final class ReleaseAssessmentHandler implements TaskHandler {

    private static final String MAIN_JAVA = "src/main/java/";

    @Override
    public TaskResult execute(TaskContext context) {
        List<Artifact> changes = context.readAll(ArtifactKeys.CHANGES_PREFIX);
        List<Artifact> approvals = context.readAll("approval/");
        Optional<VerificationReport> verification = context.find(ArtifactKeys.VERIFICATION_REPORT, VerificationReport.class);
        Optional<VerificationReport> baseline = context.find(ArtifactKeys.BASELINE_VERIFICATION, VerificationReport.class);
        Optional<JsonNode> security = context.find(ArtifactKeys.SECURITY_REVIEW, JsonNode.class);
        Optional<JsonNode> api = context.find(ArtifactKeys.API_COMPATIBILITY, JsonNode.class);
        Optional<JsonNode> contract = context.find(ArtifactKeys.API_CONTRACT, JsonNode.class);
        boolean changed = !changes.isEmpty();
        String workspaceHash = context.workspace().contentHash();

        List<Map<String, Object>> items = new ArrayList<>();
        item(items, "baseline-verified", "Baseline built and tested before any change",
                baseline.map(VerificationReport::passedRealBuild).orElse(!changed),
                baseline.map(ReleaseAssessmentHandler::describe).orElse("no baseline verification"));
        item(items, "tests-executed-and-passing", "Real test suite executed against the changed workspace and passed",
                verification.map(VerificationReport::passedRealBuild).orElse(!changed),
                verification.map(ReleaseAssessmentHandler::describe).orElse("no verification report"));
        item(items, "verified-tree-is-final", "The verified source tree is exactly the tree being released",
                verification.map(v -> v.workspaceHash().equals(workspaceHash)).orElse(!changed),
                verification.map(v -> v.workspaceHash().equals(workspaceHash) ? "hash " + abbreviate(workspaceHash)
                        : "verified " + abbreviate(v.workspaceHash()) + " but releasing " + abbreviate(workspaceHash)).orElse("n/a"));
        List<String> regressions = regressions(baseline, verification);
        item(items, "no-test-regression", "Every baseline test class still passes and no tests were lost",
                verification.isPresent() && baseline.isPresent() ? regressions.isEmpty() : !changed,
                regressions.isEmpty() ? baseline.map(b -> b.testsRun() + " -> " + verification.map(VerificationReport::testsRun).orElse(0)
                        + " tests").orElse("n/a") : String.join("; ", regressions));
        List<String> uncovered = verification.map(v -> v.acceptanceCoverage().stream()
                .filter(c -> !c.covered()).map(VerificationReport.Coverage::id).toList()).orElse(List.of());
        item(items, "acceptance-criteria-covered", "Every acceptance criterion is referenced by a passing test",
                verification.isPresent() && uncovered.isEmpty(), uncovered.isEmpty() ? "all criteria covered" : "uncovered: " + uncovered);
        item(items, "security-review-clean", "Aggregate security/compliance review has no open findings",
                security.map(s -> s.path("status").asString().equals("CLEAN")).orElse(!changed),
                security.map(s -> s.path("status").asString() + ", " + s.path("approvedFindings").size() + " approved findings").orElse("not reviewed"));
        // Without a review, only the change sets themselves can show the API was left alone: the design's
        // own change labels are claims, not evidence.
        List<String> apiPaths = changes.stream().flatMap(c -> paths(c).stream()).filter(ReleaseAssessmentHandler::isApiRelevant)
                .distinct().sorted().toList();
        item(items, "api-compatible-and-documented", "No breaking change to the documented API; new operations implemented and documented",
                api.map(a -> a.path("status").asString().equals("COMPATIBLE")).orElse(apiPaths.isEmpty()),
                api.map(a -> a.path("status").asString() + ", added " + a.path("addedOperations"))
                        .orElse(apiPaths.isEmpty() ? "not reviewed; no API-relevant file changed" : "not reviewed, but changed " + apiPaths));
        boolean docsTouched = changes.stream().anyMatch(c -> touches(c, "docs/") || touches(c, WorkspaceFacts.OPENAPI) || touches(c, "README.md"));
        // Labels may only add to what the review measured: added operations need documentation whatever they say.
        boolean docsRequired = hasApiChanges(contract) || api.map(a -> !a.path("addedOperations").isEmpty()).orElse(false);
        item(items, "documentation-updated", "Documentation updated for externally visible changes",
                !docsRequired || docsTouched, docsTouched ? "documentation changed"
                        : docsRequired ? "API changed but no documentation change" : "no documentation change");

        boolean ready = items.stream().allMatch(i -> Boolean.TRUE.equals(i.get("passed")));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("ready", ready);
        content.put("items", items);
        content.put("approvals", approvals.stream().map(Artifact::content).toList());
        content.put("changeSets", changes.stream().map(a -> a.key() + "@v" + a.version()).toList());
        content.put("workspaceHash", workspaceHash);
        long passed = items.stream().filter(i -> Boolean.TRUE.equals(i.get("passed"))).count();
        return TaskResult.of("readiness " + passed + "/" + items.size() + (ready ? " - ready for sign-off" : " - NOT READY"),
                ArtifactKeys.RELEASE_READINESS, OutputArtifact.of("release-readiness", content));
    }

    private static List<String> regressions(Optional<VerificationReport> baseline, Optional<VerificationReport> verification) {
        if (baseline.isEmpty() || verification.isEmpty() || !baseline.get().passedRealBuild()) {
            return List.of();
        }
        List<String> regressions = new ArrayList<>();
        baseline.get().passedTestClasses().stream()
                .filter(test -> !verification.get().passedTestClasses().contains(test))
                .forEach(test -> regressions.add(test + " no longer passes"));
        int before = baseline.get().testsRun() - baseline.get().skipped();
        int after = verification.get().testsRun() - verification.get().skipped();
        if (after < before) {
            regressions.add("executed tests dropped from " + before + " to " + after);
        }
        return regressions;
    }

    private static String describe(VerificationReport report) {
        return report.mode() + " " + report.outcome() + ", " + report.testsRun() + " tests" + (report.degraded() ? " (DEGRADED)" : "");
    }

    private static String abbreviate(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    private static void item(List<Map<String, Object>> items, String id, String title, boolean passed, String evidence) {
        items.add(Map.of("id", id, "title", title, "passed", passed, "evidence", evidence));
    }

    private static boolean hasApiChanges(Optional<JsonNode> contract) {
        if (contract.isEmpty()) {
            return false;
        }
        for (JsonNode operation : contract.get().path("operations")) {
            if (!operation.path("change").asString("NEW").equals("UNCHANGED")) {
                return true;
            }
        }
        return false;
    }

    private static boolean touches(Artifact changeSet, String prefix) {
        for (JsonNode file : changeSet.content().path("files")) {
            if (file.path("path").asString().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> paths(Artifact changeSet) {
        List<String> paths = new ArrayList<>();
        changeSet.content().path("files").forEach(file -> paths.add(file.path("path").asString()));
        return paths;
    }

    /**
     * Files that can change the HTTP API: main sources in an {@code api} or {@code web} package (at any depth),
     * any {@code *Controller.java}, and the OpenAPI document.
     */
    private static boolean isApiRelevant(String path) {
        if (path.equals(WorkspaceFacts.OPENAPI) || path.endsWith("Controller.java")) {
            return true;
        }
        if (!path.startsWith(MAIN_JAVA)) {
            return false;
        }
        List<String> directories = List.of(path.substring(MAIN_JAVA.length()).split("/"));
        directories = directories.subList(0, directories.size() - 1);
        return directories.contains("api") || directories.contains("web");
    }
}
