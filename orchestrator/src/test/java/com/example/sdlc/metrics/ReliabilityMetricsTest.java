package com.example.sdlc.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;

class ReliabilityMetricsTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final List<ExecutionEvent> events = new ArrayList<>();

    private void at(long seconds, EventType type, String task, Object... data) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < data.length; i += 2) {
            values.put((String) data[i], data[i + 1]);
        }
        events.add(new ExecutionEvent(events.size() + 1, t0.plusSeconds(seconds), "run", type, task, null, "", values));
    }

    private ReliabilityMetrics metrics() {
        ReliabilityMetrics metrics = ReliabilityMetrics.of(List.copyOf(events));
        events.clear();
        return metrics;
    }

    @Test
    void derivesRatesRecoveryTimeAndLatencyFromEvents() {
        at(0, EventType.RUN_STARTED, null);
        at(0, EventType.TASK_STARTED, "a", "inFlight", 1);
        at(0, EventType.TASK_STARTED, "b", "inFlight", 2);
        at(2, EventType.TASK_SUCCEEDED, "a");
        at(3, EventType.CHANGESET_APPLIED, "b");
        at(4, EventType.ATTEMPT_FAILED, "b");
        at(4, EventType.CHANGESET_ROLLED_BACK, "b", "cause", "attempt-rejected");
        at(4, EventType.RETRY_SCHEDULED, "b");
        at(4, EventType.TASK_STARTED, "b", "inFlight", 1);
        at(5, EventType.CHANGESET_APPLIED, "b");
        at(10, EventType.TASK_SUCCEEDED, "b");
        at(10, EventType.POLICY_EVALUATED, "b", "decision", "DENY");
        at(11, EventType.HUMAN_INPUT_REQUESTED, "c", "request", "hr-1");
        at(15, EventType.RUN_PAUSED, null);
        at(100, EventType.RUN_RESUMED, null);
        at(100, EventType.HUMAN_INPUT_RECEIVED, "c", "request", "hr-1");
        at(101, EventType.TASK_STARTED, "c", "inFlight", 1);
        at(102, EventType.ATTEMPT_FAILED, "c");
        at(102, EventType.TASK_FAILED, "c");
        at(103, EventType.RUN_HALTED, null, "verdict", "NOT_READY");

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.status()).isEqualTo(RunStatus.HALTED);
        assertThat(metrics.verdict()).isEqualTo("NOT_READY");
        assertThat(metrics.outcome()).isEqualTo("HALTED (NOT_READY)");
        assertThat(metrics.attempts()).isEqualTo(4);
        assertThat(metrics.retries()).isEqualTo(1);
        assertThat(metrics.retryRate()).isEqualTo(0.25);
        assertThat(metrics.taskExecutionsSucceeded()).isEqualTo(2);
        assertThat(metrics.tasksSucceeded()).isEqualTo(2);
        assertThat(metrics.tasksFailed()).isEqualTo(1);
        assertThat(metrics.taskSuccessRate()).isEqualTo(2.0 / 3);
        assertThat(metrics.rollbackRate()).isEqualTo(0.5);
        assertThat(metrics.failureRollbackRate()).isEqualTo(0.5);
        assertThat(metrics.policyDenials()).isEqualTo(1);
        assertThat(metrics.recoveries()).isEqualTo(1);
        assertThat(metrics.meanTimeToRecovery()).isEqualTo(Duration.ofSeconds(6));
        assertThat(metrics.unrecovered()).isEqualTo(1);
        assertThat(metrics.endToEnd()).isEqualTo(Duration.ofSeconds(103));
        assertThat(metrics.humanWait()).isEqualTo(Duration.ofSeconds(89));
        // Active segments 0-15 s and 100-103 s, minus 11-15 s where hr-1 was pending and nothing executed.
        assertThat(metrics.activeTime()).isEqualTo(Duration.ofSeconds(14));
        assertThat(metrics.maxConcurrency()).isEqualTo(2);
    }

    @Test
    void taskSuccessIsJudgedPerTaskByItsLastOutcome() {
        at(0, EventType.RUN_STARTED, null);
        at(0, EventType.TASK_STARTED, "a");
        at(1, EventType.TASK_SUCCEEDED, "a");
        at(1, EventType.TASK_INVALIDATED, "a");
        at(1, EventType.TASK_STARTED, "a");
        at(2, EventType.TASK_SUCCEEDED, "a");
        at(2, EventType.TASK_STARTED, "b");
        at(3, EventType.TASK_SUCCEEDED, "b");
        at(3, EventType.TASK_INVALIDATED, "b");
        at(3, EventType.TASK_STARTED, "b");
        at(4, EventType.TASK_FAILED, "b");
        at(4, EventType.RUN_HALTED, null, "verdict", "NOT_READY");

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.taskExecutionsSucceeded()).isEqualTo(3);
        assertThat(metrics.tasksSucceeded()).isEqualTo(1);
        assertThat(metrics.tasksFailed()).isEqualTo(1);
        assertThat(metrics.taskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.attemptSuccessRate()).isEqualTo(0.75);
    }

    @Test
    void rollbacksAreCountedByCauseAndOnlyFailuresCountTowardsTheFailureRate() {
        at(0, EventType.RUN_STARTED, null);
        for (int i = 0; i < 8; i++) {
            at(1, EventType.CHANGESET_APPLIED, "impl");
        }
        at(2, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "attempt-rejected");
        at(3, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "rework");
        at(4, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "invalidation");
        at(5, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "safe-stop");
        at(5, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "safe-stop");
        at(5, EventType.CHANGESET_ROLLED_BACK, "impl");
        at(6, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "orphaned");
        at(6, EventType.RUN_HALTED, null, "verdict", "NOT_READY");

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.rollbacks()).isEqualTo(7);
        assertThat(metrics.rollbacksByCause()).containsExactly(Map.entry("attempt-rejected", 1), Map.entry("invalidation", 1),
                Map.entry("orphaned", 1), Map.entry("rework", 1), Map.entry("safe-stop", 2), Map.entry("unspecified", 1));
        assertThat(metrics.rollbackRate()).isEqualTo(7.0 / 8);
        assertThat(metrics.failureRollbackRate()).isEqualTo(4.0 / 8);
    }

    @Test
    void aTaskExecutingWhileAHumanRequestIsPendingIsActiveTime() {
        at(0, EventType.RUN_STARTED, null);
        at(2, EventType.HUMAN_INPUT_REQUESTED, "a", "request", "hr-1");
        at(3, EventType.TASK_STARTED, "b");
        at(8, EventType.TASK_SUCCEEDED, "b");
        at(10, EventType.HUMAN_INPUT_RECEIVED, "a", "request", "hr-1");
        at(12, EventType.RUN_COMPLETED, null);

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.humanWait()).isEqualTo(Duration.ofSeconds(8));
        // Only 2-3 s and 8-10 s were spent purely waiting.
        assertThat(metrics.activeTime()).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void overlappingHumanRequestsAreCountedOnce() {
        at(0, EventType.RUN_STARTED, null);
        at(2, EventType.HUMAN_INPUT_REQUESTED, "a", "request", "hr-1");
        at(4, EventType.HUMAN_INPUT_REQUESTED, "b", "request", "hr-2");
        at(6, EventType.HUMAN_INPUT_RECEIVED, "a", "request", "hr-1");
        at(8, EventType.HUMAN_INPUT_RECEIVED, "b", "request", "hr-2");
        at(10, EventType.RUN_COMPLETED, null);

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.humanWait()).isEqualTo(Duration.ofSeconds(6));
        assertThat(metrics.activeTime()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void aCancelledRequestStopsTheWait() {
        at(0, EventType.RUN_STARTED, null);
        at(2, EventType.HUMAN_INPUT_REQUESTED, "a", "request", "hr-1");
        at(5, EventType.HUMAN_INPUT_CANCELLED, "a", "request", "hr-1", "reason", "invalidation");
        at(10, EventType.RUN_COMPLETED, null);

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.humanWait()).isEqualTo(Duration.ofSeconds(3));
        assertThat(metrics.activeTime()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void anIgnoredInvalidResponseNeitherEndsTheWaitNorCountsAsADecision() {
        at(0, EventType.RUN_STARTED, null);
        at(2, EventType.HUMAN_INPUT_REQUESTED, "a", "request", "hr-1");
        at(4, EventType.HUMAN_INPUT_RECEIVED, "a", "request", "hr-1", "ignored", true);
        at(9, EventType.HUMAN_INPUT_RECEIVED, "a", "request", "hr-1", "decision", "APPROVE", "reviewer", "alice");
        at(10, EventType.RUN_COMPLETED, null);

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.humanDecisions()).isEqualTo(1);
        assertThat(metrics.humanWait()).isEqualTo(Duration.ofSeconds(7));
        assertThat(metrics.activeTime()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void workBeforeACrashCountsAsActiveButTheDowntimeDoesNot() {
        at(0, EventType.RUN_STARTED, null);
        at(0, EventType.TASK_STARTED, "a");
        at(5, EventType.TASK_SUCCEEDED, "a");
        at(6, EventType.TASK_STARTED, "b");
        // process killed here; resumed a minute later
        at(60, EventType.RUN_RESUMED, null);
        at(60, EventType.TASK_STARTED, "b");
        at(63, EventType.TASK_SUCCEEDED, "b");
        at(63, EventType.RUN_COMPLETED, null);

        ReliabilityMetrics metrics = metrics();

        assertThat(metrics.endToEnd()).isEqualTo(Duration.ofSeconds(63));
        assertThat(metrics.activeTime()).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void statusFollowsTheLastLifecycleEvent() {
        at(0, EventType.RUN_STARTED, null);
        at(1, EventType.RUN_PAUSED, null);
        assertThat(metrics().status()).isEqualTo(RunStatus.AWAITING_HUMAN);

        at(0, EventType.RUN_STARTED, null);
        at(1, EventType.RUN_PAUSED, null);
        at(5, EventType.RUN_RESUMED, null);
        ReliabilityMetrics interrupted = metrics();
        assertThat(interrupted.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(interrupted.verdict()).isNull();
    }

    @Test
    void aggregatesAcrossRuns() {
        at(0, EventType.RUN_STARTED, null);
        at(0, EventType.TASK_STARTED, "a", "inFlight", 1);
        at(4, EventType.TASK_SUCCEEDED, "a");
        at(4, EventType.RUN_COMPLETED, null, "verdict", "READY");
        ReliabilityMetrics ready = metrics();
        at(0, EventType.RUN_STARTED, null);
        at(6, EventType.RUN_COMPLETED, null);
        ReliabilityMetrics completed = metrics();
        at(0, EventType.RUN_STARTED, null);
        at(1, EventType.CHANGESET_APPLIED, "impl");
        at(2, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "safe-stop");
        at(2, EventType.RUN_HALTED, null, "verdict", "NOT_READY");
        ReliabilityMetrics halted = metrics();
        at(0, EventType.RUN_STARTED, null);
        at(1, EventType.CHANGESET_APPLIED, "impl");
        at(2, EventType.CHANGESET_ROLLED_BACK, "impl", "cause", "rework");
        at(50, EventType.RUN_PAUSED, null);
        ReliabilityMetrics paused = metrics();

        AggregateMetrics aggregate = AggregateMetrics.of(List.of(ready, completed, halted, paused));

        assertThat(aggregate.runs()).isEqualTo(4);
        assertThat(aggregate.completed()).isEqualTo(2);
        assertThat(aggregate.ready()).isEqualTo(1);
        assertThat(aggregate.halted()).isEqualTo(1);
        assertThat(aggregate.awaitingHuman()).isEqualTo(1);
        assertThat(aggregate.runSuccessRate()).isEqualTo(2.0 / 3);
        assertThat(aggregate.rollbacksByCause()).containsOnly(Map.entry("safe-stop", 1), Map.entry("rework", 1));
        assertThat(aggregate.rollbackRate()).isEqualTo(1.0);
        assertThat(aggregate.failureRollbackRate()).isEqualTo(0.5);
        // Latency is averaged over finished runs only; the paused run is still open.
        assertThat(aggregate.meanEndToEnd()).isEqualTo(Duration.ofSeconds(4));
        assertThat(aggregate.maxEndToEnd()).isEqualTo(Duration.ofSeconds(6));
    }
}
