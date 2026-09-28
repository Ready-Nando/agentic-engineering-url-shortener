package com.example.sdlc.engine;

import java.util.ArrayList;
import java.util.List;

import com.example.sdlc.artifact.ArtifactRef;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

/**
 * Mutable runtime state of one task. Owned by the engine's coordinator thread (never touched by workers),
 * persisted as part of the run snapshot.
 *
 * <p>{@code generation} increases when the task is invalidated because its inputs changed (fresh attempt
 * budget); {@code attempt} counts attempts within the current generation and is bounded by the spec.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE)
public final class TaskState {

    public enum ParkedAt { CHANGE_APPROVAL, GATE, CLARIFICATION }

    /**
     * An attempt waiting for a human decision, with everything needed to continue it afterwards.
     *
     * @param approvalFingerprint for change approvals: hash of the exact deltas and policy rules shown to the
     *                            reviewer; an approval only applies a change with the same fingerprint
     * @param workspaceHash       for gate approvals: the workspace state the reviewer saw evidence for
     */
    public record Parked(ParkedAt at, AttemptResult result, int gateIndex, String requestId, String approvalFingerprint,
                         String workspaceHash) {
    }

    private String taskId;
    private TaskStatus status = TaskStatus.PENDING;
    private int generation = 1;
    private int attempt;
    private int executions;
    private boolean onFallback;
    private List<AttemptRecord> history = new ArrayList<>();
    private List<ArtifactRef> inputs = new ArrayList<>();
    private List<String> outputs = new ArrayList<>();
    private List<Feedback> feedback = new ArrayList<>();
    private Parked parked;
    private String detail;

    // Explicit so Jackson populates fields instead of guessing a creator from the public constructor.
    @JsonCreator
    private TaskState() {
    }

    public TaskState(String taskId) {
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }

    public TaskStatus status() {
        return status;
    }

    void status(TaskStatus newStatus) {
        this.status = newStatus;
    }

    public int generation() {
        return generation;
    }

    public int attempt() {
        return attempt;
    }

    public int executions() {
        return executions;
    }

    public boolean onFallback() {
        return onFallback;
    }

    public List<AttemptRecord> history() {
        return List.copyOf(history);
    }

    public List<ArtifactRef> inputs() {
        return List.copyOf(inputs);
    }

    public List<String> outputs() {
        return List.copyOf(outputs);
    }

    public List<Feedback> feedback() {
        return List.copyOf(feedback);
    }

    public Parked parked() {
        return parked;
    }

    public String detail() {
        return detail;
    }

    void detail(String newDetail) {
        this.detail = newDetail;
    }

    void startAttempt() {
        attempt++;
        executions++;
        status = TaskStatus.RUNNING;
    }

    /** An attempt orphaned by a crashed process never produced a result, so it does not count. */
    void forgetOrphanedAttempt() {
        attempt--;
        executions--;
        status = TaskStatus.PENDING;
    }

    void record(AttemptRecord record) {
        history.add(record);
    }

    void succeeded(List<ArtifactRef> consumed, List<String> produced) {
        inputs = new ArrayList<>(consumed);
        outputs = new ArrayList<>(produced);
        parked = null;
        status = TaskStatus.SUCCEEDED;
    }

    void addFeedback(Feedback item) {
        feedback.add(item);
    }

    void park(Parked newParked) {
        parked = newParked;
        status = TaskStatus.AWAITING_HUMAN;
    }

    void clearParked() {
        parked = null;
    }

    void switchToFallback() {
        onFallback = true;
        attempt = 0;
    }

    /** Invalidation because inputs changed: a new generation with a fresh attempt budget. */
    void newGeneration() {
        generation++;
        attempt = 0;
        onFallback = false;
    }
}
