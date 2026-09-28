package com.example.sdlc.capability;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.sdlc.artifact.Artifact;
import com.example.sdlc.artifact.ArtifactKeys;
import com.example.sdlc.engine.OutputArtifact;
import com.example.sdlc.engine.TaskContext;
import com.example.sdlc.engine.TaskHandler;
import com.example.sdlc.engine.TaskResult;
import com.example.sdlc.policy.ChangeContext;
import com.example.sdlc.policy.ChangePolicy;
import com.example.sdlc.policy.PolicyDecision;
import com.example.sdlc.policy.PolicyFinding;
import com.example.sdlc.policy.PolicyVerdict;
import com.example.sdlc.workspace.FileChange;
import com.example.sdlc.workspace.FileDelta;
import com.example.sdlc.workspace.Workspace;

/**
 * Defence in depth: re-evaluates the policy over the <em>aggregate</em> diff and cross-checks that every
 * approval-requiring finding is backed by a recorded human approval for the task that introduced it.
 * Findings that only exist in the aggregate (e.g. overall change size) are surfaced for release sign-off.
 * Scope (CC-05) and impact (CC-06) are per-task rules and are not re-evaluated here.
 */
final class SecurityReviewHandler implements TaskHandler {

    private final ChangePolicy policy;

    SecurityReviewHandler(ChangePolicy policy) {
        this.policy = policy;
    }

    @Override
    public TaskResult execute(TaskContext context) {
        Map<String, String> owners = WorkspaceFacts.ownersByPath(context.readAll(ArtifactKeys.CHANGES_PREFIX));
        Set<String> approved = new HashSet<>();
        for (Artifact approval : context.readAll("approval/")) {
            String task = approval.content().path("taskId").asString();
            approval.content().path("rules").forEach(rule -> approved.add(task + "|" + rule.asString()));
        }
        Workspace workspace = context.workspace();
        List<FileDelta> deltas = new ArrayList<>();
        for (String path : workspace.changedPaths()) {
            String before = workspace.readBaseline(path).orElse(null);
            String after = context.readFile(path).orElse(null);
            FileChange.Op op = before == null ? FileChange.Op.CREATE : after == null ? FileChange.Op.DELETE : FileChange.Op.EDIT;
            deltas.add(new FileDelta(op, path, before, after));
        }
        PolicyVerdict verdict = policy.evaluate(new ChangeContext(context.task().id(), List.of("**"), deltas, null, null,
                workspace::existsInBaseline));

        List<String> blocking = new ArrayList<>();
        List<String> unapproved = new ArrayList<>();
        List<String> approvedFindings = new ArrayList<>();
        List<String> aggregateFindings = new ArrayList<>();
        for (PolicyFinding finding : verdict.findings()) {
            if (finding.decision() == PolicyDecision.DENY) {
                blocking.add(finding.describe());
            } else if (finding.decision() == PolicyDecision.REQUIRE_APPROVAL && finding.path() == null) {
                // Emerges only from the combination of change sets (e.g. overall size): no single task could
                // have asked for it, so it is put in front of the release reviewer rather than blocking.
                aggregateFindings.add(finding.describe());
            } else if (finding.decision() == PolicyDecision.REQUIRE_APPROVAL) {
                String owner = owners.getOrDefault(finding.path(), "unknown");
                (approved.contains(owner + "|" + finding.ruleId()) ? approvedFindings : unapproved)
                        .add(finding.describe() + " (introduced by " + owner + ")");
            }
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("status", blocking.isEmpty() && unapproved.isEmpty() ? "CLEAN" : "ISSUES");
        content.put("filesReviewed", deltas.size());
        content.put("blocking", blocking);
        content.put("unapproved", unapproved);
        content.put("approvedFindings", approvedFindings);
        content.put("aggregateFindings", aggregateFindings);
        content.put("rulesEvaluated", ChangePolicy.RULES.size());
        return TaskResult.of("security review " + content.get("status") + ": " + deltas.size() + " files, "
                        + blocking.size() + " blocking, " + unapproved.size() + " unapproved",
                ArtifactKeys.SECURITY_REVIEW, OutputArtifact.of("security-review", content));
    }
}
