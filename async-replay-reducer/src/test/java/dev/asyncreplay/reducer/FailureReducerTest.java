package dev.asyncreplay.reducer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FailureReducerTest {
    @Test void removesIndependentNoiseWithoutLosingTheFailure() {
        var input = List.of(event("noise-1"), event("a"), event("noise-2"), event("b"), event("noise-3"));
        for (var mode : FailureReducer.Mode.values()) {
            var result = FailureReducer.reduce(input, mode, 100, events ->
                    ids(events).containsAll(Set.of("a", "b")) ? fail("target") : pass());
            assertEquals(List.of("a", "b"), result.events().stream().map(FailureReducer.Event::id).toList());
            assertTrue(result.oneMinimal());
            assertFalse(result.budgetExhausted());
        }
    }

    @Test void retainsTransitivePrerequisitesAndDistinguishesInvalidCandidates() {
        var input = List.of(event("open"), event("commit", "open"), event("check", "commit"));
        for (var mode : FailureReducer.Mode.values()) {
            var result = FailureReducer.reduce(input, mode, 100,
                    events -> ids(events).contains("check") ? fail("target") : pass());
            assertEquals(input, result.events());
            assertTrue(result.oneMinimal());
            if (mode == FailureReducer.Mode.PLAIN) assertTrue(result.statistics().structurallyInvalid() > 0);
            else assertEquals(0, result.statistics().structurallyInvalid());
        }
    }

    @Test void rejectsDifferentFailureEvenWhenItWouldMakeTheResultSmaller() {
        var input = List.of(event("a"), event("b"), event("noise"));
        var result = FailureReducer.reduce(input, FailureReducer.Mode.PLAIN, 100, events -> {
            var ids = ids(events);
            if (ids.containsAll(Set.of("a", "b"))) return fail("original-signature");
            if (ids.contains("a") || ids.contains("b")) return fail("another-bug");
            return pass();
        });
        assertEquals(Set.of("a", "b"), ids(result.events()));
        assertEquals("original-signature", result.signature());
        assertTrue(result.statistics().otherFailures() > 0);
    }

    @Test void exhaustedBudgetReturnsTheLastKnownFailingInputWithoutMinimalityClaim() {
        var input = List.of(event("a"), event("noise"));
        var result = FailureReducer.reduce(input, FailureReducer.Mode.DEPENDENCY_CLOSED, 1,
                events -> fail("target"));
        assertEquals(input, result.events());
        assertTrue(result.budgetExhausted());
        assertFalse(result.oneMinimal());
        assertEquals(1, result.statistics().evaluations());
        assertEquals(1, result.statistics().oracleCalls());
    }

    @Test void originalMustFailAndBudgetMustBePositive() {
        for (var outcome : List.of(pass(), FailureReducer.Outcome.invalid())) {
            assertThrows(IllegalArgumentException.class, () -> FailureReducer.reduce(List.of(event("a")),
                    FailureReducer.Mode.PLAIN, 10, events -> outcome));
        }
        assertThrows(IllegalArgumentException.class, () -> FailureReducer.reduce(List.of(),
                FailureReducer.Mode.PLAIN, 0, events -> fail("target")));
    }

    @Test void validatesDagBeforeAnyOracleCall() {
        var calls = new AtomicInteger();
        List<List<FailureReducer.Event>> badInputs = List.of(
                List.of(event("a"), event("a")),
                List.of(event("b", "a"), event("a")),
                List.of(event("a", "a")),
                List.of(event("a", "b"), event("b", "a")));
        for (var input : badInputs) {
            assertThrows(IllegalArgumentException.class, () -> FailureReducer.reduce(input,
                    FailureReducer.Mode.PLAIN, 10, events -> { calls.incrementAndGet(); return fail("target"); }));
        }
        assertEquals(0, calls.get());
    }

    @Test void infrastructureFailureAndNullOracleOutcomePropagate() {
        var failure = new IllegalStateException("database unavailable");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> FailureReducer.reduce(List.of(event("a")),
                FailureReducer.Mode.PLAIN, 10, events -> { throw failure; })));
        assertThrows(NullPointerException.class, () -> FailureReducer.reduce(List.of(event("a")),
                FailureReducer.Mode.PLAIN, 10, events -> null));
    }

    @Test void inputViewsAndResultsAreImmutable() {
        var dependencies = new ArrayList<>(List.of("a"));
        var b = new FailureReducer.Event("b", dependencies);
        dependencies.clear();
        assertEquals(List.of("a"), b.requires());
        var result = FailureReducer.reduce(List.of(event("a"), b), FailureReducer.Mode.PLAIN, 100, events -> {
            assertThrows(UnsupportedOperationException.class, () -> events.clear());
            return ids(events).contains("b") ? fail("target") : pass();
        });
        assertThrows(UnsupportedOperationException.class, () -> result.events().clear());
        assertThrows(IllegalArgumentException.class, () -> new FailureReducer.Event("a", List.of("b", "b")));
    }

    @Test void oneMinimalDoesNotMeanGloballySmallestForANonmonotonicOracle() {
        var input = List.of(event("a"), event("b"), event("c"), event("d"));
        FailureReducer.Oracle oracle = events -> {
            var ids = ids(events);
            return ids.size() == 4 || ids.equals(Set.of("a", "d")) ? fail("target") : pass();
        };
        var result = FailureReducer.reduce(input, FailureReducer.Mode.PLAIN, 100, oracle);
        assertTrue(result.oneMinimal());
        assertEquals(4, result.events().size());
        assertEquals(fail("target"), oracle.evaluate(List.of(event("a"), event("d"))));
    }

    @Test void emptyFailingInputIsSupportedAndOutcomeSignaturesAreValidated() {
        var result = FailureReducer.reduce(List.of(event("a")), FailureReducer.Mode.PLAIN, 100, events -> fail("target"));
        assertTrue(result.events().isEmpty());
        assertTrue(result.oneMinimal());
        assertThrows(IllegalArgumentException.class, () -> fail(" "));
        assertThrows(IllegalArgumentException.class, () -> new FailureReducer.Outcome(FailureReducer.Status.PASS, "wrong"));
        assertThrows(IllegalArgumentException.class, () -> new FailureReducer.Outcome(FailureReducer.Status.INVALID, "wrong"));
    }

    @Test void actualControlledRuntimeWorkloadReplaysAndItsFixedVariantPasses() {
        var input = ReductionWorkload.input(16, 8);
        var original = ReductionWorkload.record(input, false);
        assertEquals(fail(ReductionWorkload.SIGNATURE), original.outcome());
        assertEquals(original, ReductionWorkload.replay(input, false, original.trace()));
        assertEquals(pass(), ReductionWorkload.record(input, true).outcome());
        for (var mode : FailureReducer.Mode.values()) {
            var result = FailureReducer.reduce(input, mode, 10_000,
                    events -> ReductionWorkload.record(events, false).outcome());
            assertEquals(6, result.events().size());
            assertTrue(result.events().stream().allMatch(e -> e.id().startsWith("target:")));
            var reduced = ReductionWorkload.record(result.events(), false);
            assertEquals(original.outcome(), reduced.outcome());
            assertEquals(reduced, ReductionWorkload.replay(result.events(), false, reduced.trace()));
            assertEquals(pass(), ReductionWorkload.record(result.events(), true).outcome());
        }
    }

    @Test void experimentRefusesToOverwriteNonemptyOutput(@TempDir Path temporary) throws Exception {
        var output = temporary.resolve("existing");
        Files.createDirectories(output);
        var sentinel = output.resolve("keep.txt");
        Files.writeString(sentinel, "existing data");
        assertThrows(IllegalArgumentException.class, () -> ReducerExperiment.run(Path.of(".."), output));
        assertEquals("existing data", Files.readString(sentinel));
    }

    private static FailureReducer.Event event(String id, String... requirements) {
        return new FailureReducer.Event(id, List.of(requirements));
    }
    private static Set<String> ids(List<FailureReducer.Event> events) {
        return Set.copyOf(events.stream().map(FailureReducer.Event::id).toList());
    }
    private static FailureReducer.Outcome fail(String signature) { return FailureReducer.Outcome.fail(signature); }
    private static FailureReducer.Outcome pass() { return FailureReducer.Outcome.pass(); }
}
