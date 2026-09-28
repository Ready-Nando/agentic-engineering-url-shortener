package com.example.sdlc.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.example.sdlc.artifact.ArtifactStore;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;
import com.example.sdlc.plan.TaskSpec;
import com.example.sdlc.plan.WorkflowPlan;
import com.example.sdlc.workspace.AppliedChangeSet;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

/**
 * The complete, inspectable state of a workflow run: plan history, task states, artifacts with lineage,
 * human checkpoints and the stack of applied workspace changes. Persisted as a JSON snapshot so a paused
 * run can be resumed by another process.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE)
public final class WorkflowRun {

    private String id;
    private String scenarioId;
    private String title;
    private String requirement;
    private Instant createdAt;
    private RunStatus status = RunStatus.CREATED;
    private String statusReason;
    private String verdict;
    private List<WorkflowPlan> plans = new ArrayList<>();
    private Map<String, TaskState> tasks = new LinkedHashMap<>();
    private ArtifactStore artifacts = new ArtifactStore();
    private List<HumanRequest> humanRequests = new ArrayList<>();
    private List<AppliedChangeSet> appliedChanges = new ArrayList<>();
    private Map<String, String> settings = new TreeMap<>();
    private int replans;
    private int sequence;

    // Explicit so Jackson populates fields instead of guessing a creator from the public constructor.
    @JsonCreator
    private WorkflowRun() {
    }

    public WorkflowRun(String id, String scenarioId, String title, String requirement, Instant createdAt,
                       WorkflowPlan initialPlan, Map<String, String> settings) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.title = title;
        this.requirement = requirement;
        this.createdAt = createdAt;
        this.settings.putAll(settings);
        adoptPlan(initialPlan);
    }

    public String id() {
        return id;
    }

    public String scenarioId() {
        return scenarioId;
    }

    public String title() {
        return title;
    }

    public String requirement() {
        return requirement;
    }

    public RunStatus status() {
        return status;
    }

    public String statusReason() {
        return statusReason;
    }

    void status(RunStatus newStatus, String reason) {
        this.status = newStatus;
        this.statusReason = reason;
    }

    /** READY when release sign-off succeeded, NOT_READY when the run halted; {@code null} while undecided. */
    public String verdict() {
        return verdict;
    }

    void verdict(String newVerdict) {
        this.verdict = newVerdict;
    }

    public WorkflowPlan plan() {
        return plans.getLast();
    }

    public List<WorkflowPlan> planHistory() {
        return List.copyOf(plans);
    }

    void adoptPlan(WorkflowPlan plan) {
        plans.add(plan);
        for (TaskSpec spec : plan.tasks()) {
            tasks.computeIfAbsent(spec.id(), TaskState::new);
        }
    }

    public TaskState task(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) {
            throw new IllegalArgumentException("unknown task " + taskId);
        }
        return state;
    }

    /** States of the tasks in the current plan, in plan order. */
    public List<TaskState> activeTasks() {
        return plan().tasks().stream().map(spec -> tasks.get(spec.id())).toList();
    }

    public List<TaskState> allTasks() {
        return List.copyOf(tasks.values());
    }

    public ArtifactStore artifacts() {
        return artifacts;
    }

    public List<HumanRequest> humanRequests() {
        return List.copyOf(humanRequests);
    }

    public Optional<HumanRequest> humanRequest(String requestId) {
        return humanRequests.stream().filter(r -> r.id().equals(requestId)).findFirst();
    }

    public List<HumanRequest> pendingHumanRequests() {
        return humanRequests.stream().filter(r -> r.status() == HumanRequest.Status.PENDING).toList();
    }

    void addHumanRequest(HumanRequest request) {
        humanRequests.add(request);
    }

    void replaceHumanRequest(HumanRequest request) {
        humanRequests.replaceAll(existing -> existing.id().equals(request.id()) ? request : existing);
    }

    /**
     * Records a human answer for a pending request, e.g. supplied through the CLI while the run is paused.
     * The engine applies it when the run resumes.
     */
    public void answer(String requestId, HumanResponse response, Instant at) {
        HumanRequest request = humanRequest(requestId)
                .orElseThrow(() -> new IllegalArgumentException("unknown request " + requestId));
        if (request.status() != HumanRequest.Status.PENDING) {
            throw new IllegalStateException("request " + requestId + " is " + request.status());
        }
        validate(request, response).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        replaceHumanRequest(request.answered(response, at));
    }

    /**
     * Why {@code response} cannot answer {@code request}, if it cannot: clarifications need answers, approvals
     * need a decision, and a change request may only target the checkpoint's task or one of its prerequisites.
     */
    public Optional<String> validate(HumanRequest request, HumanResponse response) {
        boolean clarification = request.kind() == HumanRequest.Kind.CLARIFICATION;
        boolean answer = response.decision() == HumanResponse.Decision.ANSWER;
        if (clarification != answer && response.decision() != HumanResponse.Decision.REJECT) {
            return Optional.of("request " + request.id() + " is a " + request.kind() + " checkpoint and cannot be answered with "
                    + response.decision());
        }
        String target = response.changeTarget();
        if (response.decision() == HumanResponse.Decision.REQUEST_CHANGES && target != null && !target.equals(request.taskId())) {
            if (request.kind() == HumanRequest.Kind.CHANGE_APPROVAL) {
                return Optional.of("a change-set checkpoint can only send its own task (" + request.taskId() + ") back");
            }
            if (!plan().contains(request.taskId()) || !plan().ancestors(request.taskId()).contains(target)) {
                return Optional.of("'" + target + "' is not " + request.taskId() + " or one of its prerequisites");
            }
        }
        return Optional.empty();
    }

    public List<AppliedChangeSet> appliedChanges() {
        return List.copyOf(appliedChanges);
    }

    void pushApplied(AppliedChangeSet applied) {
        appliedChanges.add(applied);
    }

    void removeApplied(String changeSetId) {
        appliedChanges.removeIf(applied -> applied.id().equals(changeSetId));
    }

    public String setting(String key, String defaultValue) {
        return settings.getOrDefault(key, defaultValue);
    }

    /** Records an operator decision that changes how the run continues (e.g. the reviewer mode on resume). */
    public void updateSetting(String key, String value) {
        settings.put(key, value);
    }

    public int replans() {
        return replans;
    }

    void countReplan() {
        replans++;
    }

    String nextId(String prefix) {
        return prefix + "-" + (++sequence);
    }
}
