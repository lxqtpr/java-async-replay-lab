package dev.asyncreplay;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Versioned scheduling decisions. They do not capture arbitrary application state. */
public record RunTrace(long seed, Instant origin, List<Step> steps) {
    public RunTrace {
        Objects.requireNonNull(origin, "origin");
        steps = List.copyOf(steps);
    }

    public record Step(long timeNanos, long taskId, String label, List<Long> eligibleIds, String outcome) {
        public Step {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(outcome, "outcome");
            eligibleIds = List.copyOf(eligibleIds);
        }
    }
}
