package dev.asyncreplay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.Executor;

/**
 * Single-thread, cooperative test runtime. Only explicitly submitted tasks are controlled.
 * It does not instrument threads, the common pool, the JVM memory model or external I/O.
 */
public final class ControlledRuntime extends Clock implements Executor, AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final long seed;
    private final Instant origin;
    private final SplittableRandom random;
    private final RunTrace expected;
    private final List<Task> pending = new ArrayList<>();
    private final List<RunTrace.Step> recorded = new ArrayList<>();
    private long nowNanos;
    private long nextId;
    private int replayCursor;
    private boolean running;
    private boolean closed;

    public ControlledRuntime(long seed) { this(seed, Instant.EPOCH, null); }
    public ControlledRuntime(long seed, Instant origin) { this(seed, origin, null); }

    private ControlledRuntime(long seed, Instant origin, RunTrace expected) {
        this.seed = seed;
        this.origin = Objects.requireNonNull(origin, "origin");
        this.random = new SplittableRandom(seed);
        this.expected = expected;
    }

    public static ControlledRuntime replay(RunTrace trace) {
        Objects.requireNonNull(trace, "trace");
        return new ControlledRuntime(trace.seed(), trace.origin(), trace);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        if (zone.equals(getZone())) return this;
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId next) { return ControlledRuntime.this.withZone(next); }
            @Override public Instant instant() { return ControlledRuntime.this.instant(); }
        };
    }

    @Override public Instant instant() { checkOwner(); return origin.plusNanos(nowNanos); }
    public long elapsedNanos() { checkOwner(); return nowNanos; }
    @Override public void execute(Runnable command) { schedule("executor", Duration.ZERO, command); }

    public TaskHandle schedule(String label, Duration delay, Runnable command) {
        checkOpen();
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.contains("\t") || label.contains("\n") || label.contains("\r")) {
            throw new IllegalArgumentException("label must be nonblank and contain no tabs/newlines");
        }
        long nanos = nonnegativeNanos(delay);
        Task task = new Task(Math.incrementExact(nextId), Math.addExact(nowNanos, nanos), label, command);
        nextId = task.id;
        pending.add(task);
        return () -> {
            checkOpen();
            return pending.remove(task);
        };
    }

    public int pendingTasks() { checkOwner(); return pending.size(); }

    /** Runs one earliest task; tasks with identical deadlines are selected by seed or trace. */
    public boolean runNext() {
        checkOpen();
        if (running) throw new IllegalStateException("nested runtime driving is unsupported");
        if (pending.isEmpty()) return false;
        long deadline = earliestDeadline();
        List<Task> eligible = pending.stream().filter(t -> t.deadline == deadline)
                .sorted(Comparator.comparingLong(t -> t.id)).toList();
        List<Long> ids = eligible.stream().map(t -> t.id).toList();
        Task selected;
        RunTrace.Step replayStep = null;
        if (expected == null) {
            selected = eligible.get(random.nextInt(eligible.size()));
        } else {
            if (replayCursor >= expected.steps().size()) throw mismatch("unexpected extra task", ids);
            replayStep = expected.steps().get(replayCursor);
            if (replayStep.timeNanos() != Math.max(nowNanos, deadline) || !replayStep.eligibleIds().equals(ids)) {
                throw mismatch("eligible tasks or virtual time changed", ids);
            }
            long selectedId = replayStep.taskId();
            selected = eligible.stream().filter(t -> t.id == selectedId).findFirst()
                    .orElseThrow(() -> mismatch("recorded task is not eligible", ids));
            if (!selected.label.equals(replayStep.label())) throw mismatch("task label changed", ids);
        }
        nowNanos = Math.max(nowNanos, deadline);
        pending.remove(selected);
        running = true;
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        String outcome = "OK";
        try {
            selected.command.run();
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
            outcome = failure.getClass().getName();
        } catch (Error failure) {
            errorFailure = failure;
            outcome = failure.getClass().getName();
        } finally {
            running = false;
        }
        RunTrace.Step step = new RunTrace.Step(nowNanos, selected.id, selected.label, ids, outcome);
        recorded.add(step);
        if (replayStep != null) {
            replayCursor++;
            if (!outcome.equals(replayStep.outcome())) {
                ReplayMismatchException mismatch = mismatch("callback outcome changed", ids);
                if (runtimeFailure != null) mismatch.addSuppressed(runtimeFailure);
                if (errorFailure != null) mismatch.addSuppressed(errorFailure);
                throw mismatch;
            }
        }
        if (runtimeFailure != null) throw runtimeFailure;
        if (errorFailure != null) throw errorFailure;
        return true;
    }

    /** Advances through all pending deadlines. The budget prevents accidental infinite self-rescheduling. */
    public void drain(int maxSteps) {
        checkOpen();
        requireBudget(maxSteps);
        if (running) throw new IllegalStateException("nested runtime driving is unsupported");
        int steps = 0;
        while (!pending.isEmpty()) {
            if (steps++ == maxSteps) throw new IllegalStateException("task step budget exhausted: " + maxSteps);
            runNext();
        }
    }

    /** Runs only tasks due by the target, then moves the clock to the target. */
    public void advanceBy(Duration duration, int maxSteps) {
        checkOpen();
        requireBudget(maxSteps);
        if (running) throw new IllegalStateException("nested runtime driving is unsupported");
        long target = Math.addExact(nowNanos, nonnegativeNanos(duration));
        int steps = 0;
        while (!pending.isEmpty() && earliestDeadline() <= target) {
            if (steps++ == maxSteps) throw new IllegalStateException("task step budget exhausted: " + maxSteps);
            runNext();
        }
        nowNanos = target;
    }

    public RunTrace trace() { checkOwner(); return new RunTrace(seed, origin, recorded); }

    public void verifyReplayComplete() {
        checkOwner();
        if (expected != null && replayCursor != expected.steps().size()) {
            throw mismatch("unconsumed recorded decisions: " + (expected.steps().size() - replayCursor), List.of());
        }
    }

    /** Fails on pending tasks and incomplete replay; does not silently execute application work. */
    @Override public void close() {
        checkOwner();
        if (closed) return;
        if (running) throw new IllegalStateException("cannot close from a running callback");
        closed = true;
        int leaked = pending.size();
        pending.clear();
        if (leaked != 0) throw new IllegalStateException("pending task leak: " + leaked);
        verifyReplayComplete();
    }

    private long earliestDeadline() { return pending.stream().mapToLong(t -> t.deadline).min().orElseThrow(); }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("runtime is confined to its creating thread");
    }
    private void checkOpen() { checkOwner(); if (closed) throw new IllegalStateException("runtime is closed"); }
    private static long nonnegativeNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) throw new IllegalArgumentException("negative virtual duration");
        return duration.toNanos();
    }
    private static void requireBudget(int budget) {
        if (budget < 0) throw new IllegalArgumentException("negative step budget");
    }
    private ReplayMismatchException mismatch(String reason, List<Long> ids) {
        return new ReplayMismatchException("replay step " + replayCursor + ": " + reason + "; eligible=" + ids);
    }
    private record Task(long id, long deadline, String label, Runnable command) { }
    @FunctionalInterface public interface TaskHandle { boolean cancel(); }
}
