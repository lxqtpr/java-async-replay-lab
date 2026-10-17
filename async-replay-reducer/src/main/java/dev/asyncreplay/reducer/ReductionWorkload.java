package dev.asyncreplay.reducer;

import dev.asyncreplay.ControlledRuntime;
import dev.asyncreplay.RunTrace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A constructed lost-ack workload with known dependencies and deterministic
 * deadlines. It is an adapter smoke example, not mined production executions.
 */
public final class ReductionWorkload {
    public static final String SIGNATURE = "operation-1:duplicate-effect-after-lost-ack";
    private static final List<String> TARGET = List.of("open", "commit", "timeout", "retry", "ack", "check");

    private ReductionWorkload() { }

    public record Execution(FailureReducer.Outcome outcome, RunTrace trace, String state) { }

    /** Position counts complete three-event noise groups before the target chain. */
    public static List<FailureReducer.Event> input(int noiseGroups, int targetPosition) {
        if (noiseGroups < 0 || targetPosition < 0 || targetPosition > noiseGroups) {
            throw new IllegalArgumentException("invalid workload dimensions");
        }
        List<FailureReducer.Event> events = new ArrayList<>();
        for (int group = 0; group <= noiseGroups; group++) {
            if (group == targetPosition) {
                String previous = null;
                for (String action : TARGET) {
                    String id = "target:" + action;
                    events.add(new FailureReducer.Event(id, previous == null ? List.of() : List.of(previous)));
                    previous = id;
                }
            }
            if (group < noiseGroups) {
                String prefix = "noise-" + group;
                events.add(new FailureReducer.Event(prefix + ":open", List.of()));
                events.add(new FailureReducer.Event(prefix + ":work", List.of(prefix + ":open")));
                events.add(new FailureReducer.Event(prefix + ":close", List.of(prefix + ":work")));
            }
        }
        return List.copyOf(events);
    }

    public static Execution record(List<FailureReducer.Event> input, boolean fixed) {
        return execute(input, fixed, new ControlledRuntime(0L));
    }

    public static Execution replay(List<FailureReducer.Event> input, boolean fixed, RunTrace trace) {
        return execute(input, fixed, ControlledRuntime.replay(trace));
    }

    private static Execution execute(List<FailureReducer.Event> input, boolean fixed, ControlledRuntime runtime) {
        State state = new State();
        try (runtime) {
            for (FailureReducer.Event event : input) {
                String id = event.id();
                if (id.startsWith("target:")) {
                    String action = id.substring("target:".length());
                    int index = TARGET.indexOf(action);
                    if (index < 0) throw new IllegalArgumentException("unknown action: " + action);
                    runtime.schedule(id, Duration.ofNanos(index), () -> {
                        switch (action) {
                            case "open" -> state.opened = true;
                            case "commit" -> state.apply(fixed);
                            case "timeout" -> state.unknown = true; // original ack not received
                            case "retry" -> { if (state.unknown && !state.acknowledged) state.apply(fixed); }
                            case "ack" -> state.acknowledged = true;
                            case "check" -> {
                                if (state.effects > 1) state.outcome = FailureReducer.Outcome.fail(SIGNATURE);
                            }
                            default -> throw new IllegalArgumentException(action);
                        }
                    });
                } else if (id.startsWith("noise-")) {
                    int separator = id.indexOf(':');
                    int group = Integer.parseInt(id.substring(6, separator));
                    String action = id.substring(separator + 1);
                    int offset = List.of("open", "work", "close").indexOf(action);
                    if (group < 0 || offset < 0) throw new IllegalArgumentException("unknown noise: " + id);
                    long time = Math.addExact(100L, Math.addExact(Math.multiplyExact((long) group, 3L), offset));
                    runtime.schedule(id, Duration.ofNanos(time), () -> {
                        if (action.equals("close")) state.noiseCompleted++;
                    });
                } else throw new IllegalArgumentException("unknown event: " + id);
            }
            runtime.drain(Math.addExact(input.size(), 1));
            runtime.verifyReplayComplete();
            return new Execution(state.outcome, runtime.trace(), state.describe());
        }
    }

    private static final class State {
        private final Set<String> appliedKeys = new HashSet<>();
        private boolean opened;
        private boolean unknown;
        private boolean acknowledged;
        private int effects;
        private int noiseCompleted;
        private FailureReducer.Outcome outcome = FailureReducer.Outcome.pass();

        void apply(boolean fixed) {
            if (!opened) throw new IllegalStateException("effect before open");
            if (!fixed || appliedKeys.add("operation-1")) effects++;
        }

        String describe() {
            return "effects=" + effects + ";acknowledged=" + acknowledged + ";noiseCompleted=" + noiseCompleted;
        }
    }
}
