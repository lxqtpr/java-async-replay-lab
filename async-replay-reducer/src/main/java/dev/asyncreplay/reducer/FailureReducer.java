package dev.asyncreplay.reducer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complement-based ddmin-style baseline over a caller-declared ordered DAG.
 * The oracle must be deterministic and fresh per call. This reduces workload
 * events, not arbitrary strict runtime trace files or concurrent JVM executions.
 */
public final class FailureReducer {
    private FailureReducer() { }

    public enum Mode { PLAIN, DEPENDENCY_CLOSED }
    public enum Status { PASS, FAIL, INVALID }

    /** Dependencies must precede this event in the original input. */
    public record Event(String id, List<String> requires) {
        public Event {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) throw new IllegalArgumentException("blank event ID");
            requires = List.copyOf(requires);
            if (requires.stream().distinct().count() != requires.size()) {
                throw new IllegalArgumentException("duplicate dependency: " + id);
            }
        }
    }

    public record Outcome(Status status, String signature) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(signature, "signature");
            if ((status == Status.FAIL) == signature.isBlank()) {
                throw new IllegalArgumentException("only FAIL must have a nonblank signature");
            }
        }
        public static Outcome pass() { return new Outcome(Status.PASS, ""); }
        public static Outcome invalid() { return new Outcome(Status.INVALID, ""); }
        public static Outcome fail(String signature) { return new Outcome(Status.FAIL, signature); }
    }

    @FunctionalInterface public interface Oracle {
        /** Setup/infrastructure exceptions propagate; they are never accepted as failures. */
        Outcome evaluate(List<Event> events);
    }

    public record Statistics(int evaluations, int oracleCalls, int structurallyInvalid,
                             int otherFailures, int cacheHits) { }

    /** oneMinimal is relative to this mode's deletions, declared DAG, and oracle. */
    public record Result(List<Event> events, String signature, boolean oneMinimal,
                         boolean budgetExhausted, Statistics statistics) {
        public Result { events = List.copyOf(events); }
    }

    /** A budget counts uncached candidates, including structurally invalid ones. */
    public static Result reduce(List<Event> input, Mode mode, int maxEvaluations, Oracle oracle) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(oracle, "oracle");
        if (maxEvaluations < 1) throw new IllegalArgumentException("budget must be positive");
        List<Event> current = List.copyOf(input);
        validateOriginal(current);
        Context context = new Context(maxEvaluations, oracle);
        Outcome original = context.evaluate(current);
        if (original.status() != Status.FAIL) throw new IllegalArgumentException("original must fail");
        context.signature = original.signature();
        int granularity = 2;
        while (!current.isEmpty()) {
            int parts = Math.min(granularity, current.size());
            boolean reduced = false;
            for (int part = 0; part < parts; part++) {
                int from = part * current.size() / parts;
                int to = (part + 1) * current.size() / parts;
                List<Event> candidate = delete(current, from, to, mode);
                Outcome outcome = context.evaluate(candidate);
                if (outcome == null) return context.result(current, false, true);
                if (outcome.status() == Status.FAIL && outcome.signature().equals(context.signature)) {
                    current = candidate;
                    granularity = Math.max(2, parts - 1);
                    reduced = true;
                    break;
                }
            }
            if (reduced) continue;
            if (parts == current.size()) return context.result(current, true, false);
            granularity = (int) Math.min((long) current.size(), (long) parts * 2L);
        }
        return context.result(current, true, false);
    }

    private static List<Event> delete(List<Event> current, int from, int to, Mode mode) {
        Set<String> removed = new HashSet<>();
        for (int index = from; index < to; index++) removed.add(current.get(index).id());
        List<Event> kept = new ArrayList<>();
        // Topological input order makes one pass sufficient for transitive removal.
        for (Event event : current) {
            if (mode == Mode.DEPENDENCY_CLOSED && event.requires().stream().anyMatch(removed::contains)) {
                removed.add(event.id());
            }
            if (!removed.contains(event.id())) kept.add(event);
        }
        return List.copyOf(kept);
    }

    private static void validateOriginal(List<Event> events) {
        Set<String> seen = new HashSet<>();
        for (Event event : events) {
            if (seen.contains(event.id())) throw new IllegalArgumentException("duplicate ID: " + event.id());
            if (!seen.containsAll(event.requires())) {
                throw new IllegalArgumentException("dependency missing, cyclic or out of order: " + event.id());
            }
            seen.add(event.id());
        }
    }

    private static boolean validCandidate(List<Event> events) {
        Set<String> seen = new HashSet<>();
        for (Event event : events) {
            if (!seen.containsAll(event.requires())) return false;
            seen.add(event.id());
        }
        return true;
    }

    private static final class Context {
        private final int maxEvaluations;
        private final Oracle oracle;
        private final Map<List<String>, Outcome> cache = new HashMap<>();
        private String signature = "";
        private int evaluations;
        private int oracleCalls;
        private int structurallyInvalid;
        private int otherFailures;
        private int cacheHits;

        Context(int maxEvaluations, Oracle oracle) {
            this.maxEvaluations = maxEvaluations;
            this.oracle = oracle;
        }

        Outcome evaluate(List<Event> events) {
            List<String> key = events.stream().map(Event::id).toList();
            Outcome cached = cache.get(key);
            if (cached != null) { cacheHits++; return cached; }
            if (evaluations == maxEvaluations) return null;
            evaluations++;
            Outcome result;
            if (!validCandidate(events)) {
                structurallyInvalid++;
                result = Outcome.invalid();
            } else {
                oracleCalls++;
                result = Objects.requireNonNull(oracle.evaluate(List.copyOf(events)), "oracle outcome");
                if (result.status() == Status.FAIL && !signature.isEmpty()
                        && !signature.equals(result.signature())) otherFailures++;
            }
            cache.put(key, result);
            return result;
        }

        Result result(List<Event> events, boolean oneMinimal, boolean exhausted) {
            return new Result(events, signature, oneMinimal, exhausted,
                    new Statistics(evaluations, oracleCalls, structurallyInvalid, otherFailures, cacheHits));
        }
    }
}
