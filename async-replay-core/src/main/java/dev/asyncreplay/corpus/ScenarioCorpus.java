package dev.asyncreplay.corpus;

import dev.asyncreplay.ControlledRuntime;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Small logical bug/fixed models. Equal-deadline callback order is the only
 * explored uncertainty; no real threads, network, storage or external effects
 * are controlled. Each terminal oracle runs after all application callbacks.
 */
public final class ScenarioCorpus {
    private static final List<String> IDS = List.of(
            "timeout-late-effect", "unknown-result-retry", "cancel-retry", "callback-after-close");
    private static final Duration RACE_TIME = Duration.ofSeconds(1);
    private static final Duration CHECK_TIME = Duration.ofSeconds(4);

    private ScenarioCorpus() { }

    public static List<String> ids() { return IDS; }

    /** Install exactly one independent scenario on a fresh, undriven runtime. */
    public static Observation install(String id, ControlledRuntime runtime, boolean fixed) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runtime, "runtime");
        ScenarioObservation observation = switch (id) {
            case "timeout-late-effect" -> new TimeoutLateEffect(fixed);
            case "unknown-result-retry" -> new UnknownResultRetry(fixed);
            case "cancel-retry" -> new CancelRetry(fixed);
            case "callback-after-close" -> new CallbackAfterClose(fixed);
            default -> throw new IllegalArgumentException("unknown scenario: " + id);
        };
        observation.install(runtime);
        runtime.schedule(id + ":check", CHECK_TIME, observation::assertInvariant);
        return observation;
    }

    public interface Observation {
        /** Deterministic diagnostics: no object identities or wall-clock values. */
        String state();
        /** Application oracle; normally called by the scheduled terminal check. */
        void assertInvariant();
    }

    public static final class InvariantViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String signature;

        private InvariantViolation(String signature, String detail) {
            super(signature + ": " + detail);
            this.signature = signature;
        }

        /** Stable scenario:invariant identity, independent of seed or stack trace. */
        public String signature() { return signature; }
    }

    private abstract static class ScenarioObservation implements Observation {
        final String id;
        final boolean fixed;

        ScenarioObservation(String id, boolean fixed) { this.id = id; this.fixed = fixed; }
        abstract void install(ControlledRuntime runtime);

        final void require(boolean condition, String invariant, String detail) {
            if (!condition) throw new InvariantViolation(id + ":" + invariant, detail);
        }
    }

    /** Timeout wins => no effect. Successful completion wins => exactly one effect. */
    private static final class TimeoutLateEffect extends ScenarioObservation {
        private String result = "pending";
        private int effects;

        TimeoutLateEffect(boolean fixed) { super("timeout-late-effect", fixed); }

        @Override void install(ControlledRuntime runtime) {
            runtime.schedule(id + ":timeout", RACE_TIME, () -> {
                if (result.equals("pending")) result = "timeout";
            });
            runtime.schedule(id + ":completion", RACE_TIME, () -> {
                if (fixed) {
                    if (result.equals("pending")) { result = "completed"; effects++; }
                } else {
                    effects++; // defect: effect is published even after timeout completed the result
                    if (result.equals("pending")) result = "completed";
                }
            });
        }

        @Override public String state() { return "result=" + result + ";effects=" + effects; }

        @Override public void assertInvariant() {
            require(!result.equals("pending"), "terminal-result", state());
            require(!result.equals("timeout") || effects == 0, "no-effect-after-timeout", state());
            require(!result.equals("completed") || effects == 1, "one-effect-on-completion", state());
        }
    }

    /**
     * An effect commits before its acknowledgement. A retry timer races the ack.
     * A stable logical-operation key deduplicates an already queued retry; merely
     * learning the result later cannot retract that retry. Atomic apply+dedup is
     * an explicit assumption of this in-memory model, not an external-I/O claim.
     */
    private static final class UnknownResultRetry extends ScenarioObservation {
        private final Set<String> appliedKeys = new HashSet<>();
        private boolean acknowledged;
        private boolean retryQueued;
        private int attempts;
        private int effects;
        private int deduplicated;

        UnknownResultRetry(boolean fixed) { super("unknown-result-retry", fixed); }

        @Override void install(ControlledRuntime runtime) {
            runtime.schedule(id + ":initial-commit", Duration.ZERO, () -> {
                attempts++;
                apply(fixed ? "operation-1" : "operation-1:attempt-1");
            });
            runtime.schedule(id + ":acknowledgement", RACE_TIME, () -> acknowledged = true);
            runtime.schedule(id + ":retry-timer", RACE_TIME, () -> {
                if (!acknowledged) {
                    retryQueued = true;
                    runtime.schedule(id + ":retry-commit", Duration.ofSeconds(1), () -> {
                        attempts++;
                        apply(fixed ? "operation-1" : "operation-1:attempt-2");
                    });
                }
            });
        }

        private void apply(String key) {
            if (appliedKeys.add(key)) effects++;
            else deduplicated++;
        }

        @Override public String state() {
            return "result=" + (acknowledged ? "acknowledged" : "unknown") + ";effects=" + effects
                    + ";attempts=" + attempts + ";retryQueued=" + retryQueued + ";deduplicated=" + deduplicated;
        }

        @Override public void assertInvariant() {
            require(acknowledged, "result-eventually-known", state());
            require(attempts >= 1, "operation-attempted", state());
            require(effects == 1, "one-effect-per-logical-operation", state());
        }
    }

    /**
     * Cancellation targets attempt 1 while a retry replaces the current handle.
     * Cancelling a mutable global handle may cancel attempt 2. Capturing the
     * intended attempt's handle preserves useful retry work in either order.
     */
    private static final class CancelRetry extends ScenarioObservation {
        private ControlledRuntime.TaskHandle original;
        private ControlledRuntime.TaskHandle current;
        private boolean retryStarted;
        private boolean retryCompleted;
        private int originalEffects;
        private int effects;

        CancelRetry(boolean fixed) { super("cancel-retry", fixed); }

        @Override void install(ControlledRuntime runtime) {
            original = runtime.schedule(id + ":original-completion", Duration.ofSeconds(2), () -> originalEffects++);
            current = original;
            runtime.schedule(id + ":cancel-original", RACE_TIME, () -> {
                if (fixed) original.cancel();
                else current.cancel(); // defect: the current handle may already belong to the retry
            });
            runtime.schedule(id + ":start-retry", RACE_TIME, () -> {
                retryStarted = true;
                original.cancel(); // supersede attempt 1 regardless of the cancellation callback order
                current = runtime.schedule(id + ":retry-completion", Duration.ofSeconds(1), () -> {
                    effects++;
                    retryCompleted = true;
                });
            });
        }

        @Override public String state() {
            return "result=" + (retryCompleted ? "retry-completed" : "retry-pending") + ";effects=" + effects
                    + ";retryStarted=" + retryStarted + ";originalEffects=" + originalEffects;
        }

        @Override public void assertInvariant() {
            require(originalEffects == 0, "superseded-attempt-has-no-effect", state());
            require(retryStarted && retryCompleted && effects == 1, "retry-survives-stale-cancel", state());
        }
    }

    /** A callback may write an open logical resource, but may never write after close. */
    private static final class CallbackAfterClose extends ScenarioObservation {
        private boolean closed;
        private String result = "pending";
        private int effects;
        private int writesAfterClose;

        CallbackAfterClose(boolean fixed) { super("callback-after-close", fixed); }

        @Override void install(ControlledRuntime runtime) {
            runtime.schedule(id + ":close-resource", RACE_TIME, () -> closed = true);
            runtime.schedule(id + ":completion", RACE_TIME, () -> {
                if (fixed && closed) { result = "discarded-closed"; return; }
                if (closed) writesAfterClose++; // defect: callback uses an already closed logical resource
                effects++;
                result = "written";
            });
        }

        @Override public String state() {
            return "result=" + result + ";effects=" + effects + ";closed=" + closed
                    + ";writesAfterClose=" + writesAfterClose;
        }

        @Override public void assertInvariant() {
            require(closed && !result.equals("pending"), "resource-lifecycle-completed", state());
            require(writesAfterClose == 0, "no-write-after-close", state());
            require(!result.equals("written") || effects == 1, "open-callback-does-useful-work", state());
            require(!result.equals("discarded-closed") || effects == 0, "closed-callback-is-discarded", state());
        }
    }
}
