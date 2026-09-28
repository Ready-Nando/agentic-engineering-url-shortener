package com.example.sdlc.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import com.example.sdlc.engine.EventType;
import com.example.sdlc.engine.ExecutionEvent;
import com.example.sdlc.engine.RunStatus;

/**
 * Reliability metrics of one run, derived only from its event log (never from counters kept elsewhere), so
 * they can be recomputed and audited from {@code events.jsonl}.
 *
 * <p>Task success is judged per distinct task by its last outcome event; {@code taskExecutionsSucceeded}
 * counts every successful execution (a reworked task succeeds more than once). Rollbacks are split by the
 * {@code cause} the engine records: {@code failureRollbackRate} only counts rollbacks forced by a failure
 * ({@link #FAILURE_CAUSES}), not the planned undo of a rework or invalidation.
 *
 * <p>MTTR is measured per task from the first failed attempt of a failure episode to the next success of
 * that task (retry, fallback or rework); episodes that never recover are counted as unrecovered. When the
 * recovery goes through a human checkpoint, MTTR includes the human's decision latency.
 *
 * <p>Active time is the time inside run segments ({@code RUN_STARTED}/{@code RUN_RESUMED} until the run
 * pauses, completes or halts, or until its last event before a crash) minus the time in which at least one
 * human request is pending and no task attempt is executing. An attempt executes from its {@code TASK_STARTED}
 * until the next event logged for that task. The log records when a result was handled, not when the worker
 * finished: while an interactive reviewer blocks the coordinator, attempts that were in flight are only logged
 * after the answer, so that wait counts as active time. Human wait is the time at least one request was
 * pending, overlapping requests counted once; a response the engine ignored as invalid neither ends the wait
 * nor counts as a decision.
 */
public record ReliabilityMetrics(
        RunStatus status,
        String verdict,
        int taskExecutionsSucceeded,
        int tasksSucceeded,
        int tasksFailed,
        double taskSuccessRate,
        int attempts,
        int failedAttempts,
        double attemptSuccessRate,
        int retries,
        double retryRate,
        int fallbacks,
        int reworks,
        int changeSetsApplied,
        int rollbacks,
        Map<String, Integer> rollbacksByCause,
        double rollbackRate,
        double failureRollbackRate,
        int invalidations,
        int planRevisions,
        int policyEvaluations,
        int policyDenials,
        int humanRequests,
        int humanDecisions,
        int recoveries,
        int unrecovered,
        Duration meanTimeToRecovery,
        Duration endToEnd,
        Duration activeTime,
        Duration humanWait,
        int maxConcurrency) {

    /** Rollback causes that undo a failed or abandoned attempt, as opposed to planned re-work. */
    public static final Set<String> FAILURE_CAUSES = Set.of("attempt-rejected", "safe-stop", "orphaned");
    /** Recorded for rollbacks whose event carries no {@code cause}. */
    public static final String UNSPECIFIED_CAUSE = "unspecified";

    private static final Set<EventType> SEGMENT_START = Set.of(EventType.RUN_STARTED, EventType.RUN_RESUMED);
    private static final Set<EventType> SEGMENT_END = Set.of(EventType.RUN_PAUSED, EventType.RUN_COMPLETED, EventType.RUN_HALTED);

    public ReliabilityMetrics {
        rollbacksByCause = Collections.unmodifiableMap(new TreeMap<>(rollbacksByCause));
    }

    /** One-line outcome for listings, e.g. {@code COMPLETED (READY)}. */
    public String outcome() {
        return status + (verdict == null ? "" : " (" + verdict + ")");
    }

    public static ReliabilityMetrics of(List<ExecutionEvent> events) {
        if (events.isEmpty()) {
            return new ReliabilityMetrics(RunStatus.CREATED, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0);
        }
        int executionsSucceeded = count(events, EventType.TASK_SUCCEEDED);
        Map<String, EventType> lastOutcome = new HashMap<>();
        events.stream().filter(e -> e.taskId() != null && (e.type() == EventType.TASK_SUCCEEDED || e.type() == EventType.TASK_FAILED))
                .forEach(e -> lastOutcome.put(e.taskId(), e.type()));
        int tasksSucceeded = (int) lastOutcome.values().stream().filter(t -> t == EventType.TASK_SUCCEEDED).count();
        int tasksFailed = lastOutcome.size() - tasksSucceeded;
        int attempts = count(events, EventType.TASK_STARTED);
        int failedAttempts = count(events, EventType.ATTEMPT_FAILED);
        int retries = count(events, EventType.RETRY_SCHEDULED);
        int applied = count(events, EventType.CHANGESET_APPLIED);
        Map<String, Integer> rollbacksByCause = new TreeMap<>();
        events.stream().filter(e -> e.type() == EventType.CHANGESET_ROLLED_BACK)
                .forEach(e -> rollbacksByCause.merge(Objects.requireNonNullElse(e.text("cause"), UNSPECIFIED_CAUSE), 1, Integer::sum));
        int rollbacks = rollbacksByCause.values().stream().mapToInt(Integer::intValue).sum();
        int reworks = (int) events.stream().filter(e -> e.type() == EventType.REWORK_REQUESTED && Boolean.TRUE.equals(e.data().get("possible"))).count();
        int denials = (int) events.stream().filter(e -> e.type() == EventType.POLICY_EVALUATED && "DENY".equals(e.text("decision"))).count();
        int maxConcurrency = events.stream().filter(e -> e.type() == EventType.TASK_STARTED)
                .mapToInt(e -> e.data().get("inFlight") instanceof Number n ? n.intValue() : 1).max().orElse(0);

        List<Duration> recoveries = new ArrayList<>();
        Map<String, Instant> openEpisodes = new HashMap<>();
        for (ExecutionEvent event : events) {
            if (event.taskId() == null) {
                continue;
            }
            if (event.type() == EventType.ATTEMPT_FAILED) {
                openEpisodes.putIfAbsent(event.taskId(), event.at());
            } else if (event.type() == EventType.TASK_SUCCEEDED && openEpisodes.containsKey(event.taskId())) {
                recoveries.add(Duration.between(openEpisodes.remove(event.taskId()), event.at()));
            }
        }
        Duration mttr = recoveries.isEmpty() ? Duration.ZERO
                : recoveries.stream().reduce(Duration.ZERO, Duration::plus).dividedBy(recoveries.size());

        Instant last = events.getLast().at();
        Timeline timeline = new Timeline(segments(events), humanWaits(events, last), executions(events, last));
        ExecutionEvent lifecycle = lastLifecycleEvent(events);

        return new ReliabilityMetrics(status(lifecycle), lifecycle == null ? null : lifecycle.text("verdict"), executionsSucceeded,
                tasksSucceeded, tasksFailed, ratio(tasksSucceeded, tasksSucceeded + tasksFailed),
                attempts, failedAttempts, ratio(executionsSucceeded, attempts), retries, ratio(retries, attempts),
                count(events, EventType.FALLBACK_ACTIVATED), reworks, applied, rollbacks, rollbacksByCause,
                ratio(rollbacks, applied), ratio(failureRollbacks(rollbacksByCause), applied),
                count(events, EventType.TASK_INVALIDATED), count(events, EventType.PLAN_REVISED),
                count(events, EventType.POLICY_EVALUATED), denials, count(events, EventType.HUMAN_INPUT_REQUESTED),
                (int) events.stream().filter(ReliabilityMetrics::isAppliedDecision).count(), recoveries.size(), openEpisodes.size(), mttr,
                Duration.between(events.getFirst().at(), last), timeline.activeTime(), timeline.humanWait(), maxConcurrency);
    }

    /** A human decision the engine applied; responses it ignored as invalid leave the request pending. */
    private static boolean isAppliedDecision(ExecutionEvent event) {
        return event.type() == EventType.HUMAN_INPUT_RECEIVED && !Boolean.TRUE.equals(event.data().get("ignored"));
    }

    static int failureRollbacks(Map<String, Integer> rollbacksByCause) {
        return FAILURE_CAUSES.stream().mapToInt(cause -> rollbacksByCause.getOrDefault(cause, 0)).sum();
    }

    private static ExecutionEvent lastLifecycleEvent(List<ExecutionEvent> events) {
        return events.reversed().stream()
                .filter(e -> SEGMENT_START.contains(e.type()) || SEGMENT_END.contains(e.type()))
                .findFirst().orElse(null);
    }

    private static RunStatus status(ExecutionEvent lifecycle) {
        if (lifecycle == null) {
            return RunStatus.RUNNING;
        }
        return switch (lifecycle.type()) {
            case RUN_COMPLETED -> RunStatus.COMPLETED;
            case RUN_HALTED -> RunStatus.HALTED;
            case RUN_PAUSED -> RunStatus.AWAITING_HUMAN;
            default -> RunStatus.RUNNING;
        };
    }

    private record Interval(Instant start, Instant end) {
        boolean covers(Interval other) {
            return !start.isAfter(other.start()) && !end.isBefore(other.end());
        }
    }

    /**
     * Walks the run in slices between consecutive interval boundaries. Inside a slice every interval either
     * covers it completely or not at all, so overlapping waits or executions are naturally counted once.
     */
    private record Timeline(List<Interval> segments, List<Interval> waits, List<Interval> executions) {

        Duration activeTime() {
            return total(slice -> covered(segments, slice) && (!covered(waits, slice) || covered(executions, slice)));
        }

        Duration humanWait() {
            return total(slice -> covered(waits, slice));
        }

        private Duration total(Predicate<Interval> counts) {
            TreeSet<Instant> cuts = new TreeSet<>();
            for (List<Interval> intervals : List.of(segments, waits, executions)) {
                intervals.forEach(i -> {
                    cuts.add(i.start());
                    cuts.add(i.end());
                });
            }
            Duration total = Duration.ZERO;
            Instant previous = null;
            for (Instant cut : cuts) {
                if (previous != null) {
                    Interval slice = new Interval(previous, cut);
                    if (counts.test(slice)) {
                        total = total.plus(Duration.between(previous, cut));
                    }
                }
                previous = cut;
            }
            return total;
        }

        private static boolean covered(List<Interval> intervals, Interval slice) {
            return intervals.stream().anyMatch(i -> i.covers(slice));
        }
    }

    private static List<Interval> segments(List<ExecutionEvent> events) {
        List<Interval> segments = new ArrayList<>();
        Instant start = null;
        Instant previous = null;
        for (ExecutionEvent event : events) {
            if (SEGMENT_START.contains(event.type())) {
                if (start != null) {
                    // The process died without pausing or finishing: its segment ends with its last event.
                    segments.add(new Interval(start, previous));
                }
                start = event.at();
            } else if (SEGMENT_END.contains(event.type()) && start != null) {
                segments.add(new Interval(start, event.at()));
                start = null;
            }
            previous = event.at();
        }
        if (start != null) {
            segments.add(new Interval(start, events.getLast().at()));
        }
        return segments;
    }

    private static List<Interval> humanWaits(List<ExecutionEvent> events, Instant end) {
        Map<String, Instant> requested = new HashMap<>();
        List<Interval> waits = new ArrayList<>();
        for (ExecutionEvent event : events) {
            String request = event.text("request");
            if (request == null) {
                continue;
            }
            if (event.type() == EventType.HUMAN_INPUT_REQUESTED) {
                requested.putIfAbsent(request, event.at());
            } else if ((event.type() == EventType.HUMAN_INPUT_CANCELLED || isAppliedDecision(event)) && requested.containsKey(request)) {
                waits.add(new Interval(requested.remove(request), event.at()));
            }
        }
        requested.values().forEach(start -> waits.add(new Interval(start, end)));
        return waits;
    }

    private static List<Interval> executions(List<ExecutionEvent> events, Instant end) {
        Map<String, Instant> started = new HashMap<>();
        List<Interval> executions = new ArrayList<>();
        for (ExecutionEvent event : events) {
            if (event.taskId() == null) {
                continue;
            }
            Instant start = started.remove(event.taskId());
            if (start != null) {
                executions.add(new Interval(start, event.at()));
            }
            if (event.type() == EventType.TASK_STARTED) {
                started.put(event.taskId(), event.at());
            }
        }
        started.values().forEach(start -> executions.add(new Interval(start, end)));
        return executions;
    }

    private static int count(List<ExecutionEvent> events, EventType type) {
        return (int) events.stream().filter(e -> e.type() == type).count();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
