package com.example.sdlc.metrics;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.sdlc.engine.RunStatus;

/**
 * Portfolio view across persisted runs: run-level success rate plus summed/averaged run metrics.
 * {@code completed} counts every completed run, {@code ready} those that also ended with a READY release verdict.
 */
public record AggregateMetrics(
        int runs,
        int completed,
        int ready,
        int halted,
        int awaitingHuman,
        double runSuccessRate,
        int attempts,
        int retries,
        double retryRate,
        int changeSetsApplied,
        int rollbacks,
        Map<String, Integer> rollbacksByCause,
        double rollbackRate,
        double failureRollbackRate,
        int recoveries,
        Duration meanTimeToRecovery,
        Duration meanEndToEnd,
        Duration meanActiveTime,
        Duration maxEndToEnd) {

    public AggregateMetrics {
        rollbacksByCause = Collections.unmodifiableMap(new TreeMap<>(rollbacksByCause));
    }

    public static AggregateMetrics of(List<ReliabilityMetrics> runs) {
        int completed = count(runs, RunStatus.COMPLETED);
        int ready = (int) runs.stream().filter(m -> m.status() == RunStatus.COMPLETED && "READY".equals(m.verdict())).count();
        int halted = count(runs, RunStatus.HALTED);
        int paused = count(runs, RunStatus.AWAITING_HUMAN);
        int attempts = runs.stream().mapToInt(ReliabilityMetrics::attempts).sum();
        int retries = runs.stream().mapToInt(ReliabilityMetrics::retries).sum();
        int applied = runs.stream().mapToInt(ReliabilityMetrics::changeSetsApplied).sum();
        Map<String, Integer> byCause = new TreeMap<>();
        runs.forEach(m -> m.rollbacksByCause().forEach((cause, n) -> byCause.merge(cause, n, Integer::sum)));
        int rollbacks = runs.stream().mapToInt(ReliabilityMetrics::rollbacks).sum();
        int failureRollbacks = ReliabilityMetrics.failureRollbacks(byCause);
        int recoveries = runs.stream().mapToInt(ReliabilityMetrics::recoveries).sum();
        Duration recoveryTotal = runs.stream().map(m -> m.meanTimeToRecovery().multipliedBy(m.recoveries())).reduce(Duration.ZERO, Duration::plus);
        List<ReliabilityMetrics> finished = runs.stream().filter(m -> m.status().isFinal()).toList();
        return new AggregateMetrics(runs.size(), completed, ready, halted, paused,
                completed + halted == 0 ? 0 : (double) completed / (completed + halted),
                attempts, retries, attempts == 0 ? 0 : (double) retries / attempts, applied, rollbacks, byCause,
                applied == 0 ? 0 : (double) rollbacks / applied, applied == 0 ? 0 : (double) failureRollbacks / applied, recoveries,
                recoveries == 0 ? Duration.ZERO : recoveryTotal.dividedBy(recoveries),
                mean(finished.stream().map(ReliabilityMetrics::endToEnd).toList()),
                mean(finished.stream().map(ReliabilityMetrics::activeTime).toList()),
                finished.stream().map(ReliabilityMetrics::endToEnd).max(Duration::compareTo).orElse(Duration.ZERO));
    }

    private static int count(List<ReliabilityMetrics> runs, RunStatus status) {
        return (int) runs.stream().filter(m -> m.status() == status).count();
    }

    private static Duration mean(List<Duration> durations) {
        return durations.isEmpty() ? Duration.ZERO : durations.stream().reduce(Duration.ZERO, Duration::plus).dividedBy(durations.size());
    }
}
