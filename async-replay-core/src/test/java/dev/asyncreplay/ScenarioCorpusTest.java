package dev.asyncreplay;

import dev.asyncreplay.corpus.ScenarioCorpus;
import dev.asyncreplay.corpus.ScenarioCorpus.InvariantViolation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioCorpusTest {
    @Test void catalogIsStableAndRejectsAnUnknownScenario() {
        assertEquals(List.of("timeout-late-effect", "unknown-result-retry", "cancel-retry", "callback-after-close"),
                ScenarioCorpus.ids());
        assertThrows(UnsupportedOperationException.class, () -> ScenarioCorpus.ids().add("unexpected"));
        try (var runtime = new ControlledRuntime(0)) {
            assertThrows(IllegalArgumentException.class, () -> ScenarioCorpus.install("unknown", runtime, false));
            assertEquals(0, runtime.pendingTasks());
        }
    }

    @Test void timeoutIsOrderSensitiveAndFixedCompletionStillPublishesAUsefulEffect() {
        var cases = explore("timeout-late-effect", "no-effect-after-timeout");
        assertTrue(cases.fixed.stream().anyMatch(s -> s.equals("result=completed;effects=1")));
        assertTrue(cases.fixed.stream().anyMatch(s -> s.equals("result=timeout;effects=0")));
        assertTrue(cases.buggyFailed.stream().allMatch(s -> s.equals("result=timeout;effects=1")));
        assertTrue(cases.buggyPassed.stream().allMatch(s -> s.equals("result=completed;effects=1")));
    }

    @Test void retryAfterAnUnknownResultUsesOneStableLogicalOperationKey() {
        var cases = explore("unknown-result-retry", "one-effect-per-logical-operation");
        assertTrue(cases.fixed.stream().allMatch(s -> s.startsWith("result=acknowledged;effects=1;")));
        assertTrue(cases.fixed.stream().anyMatch(s -> s.contains("attempts=2;retryQueued=true;deduplicated=1")),
                "the fix must execute and deduplicate a retry, not suppress every retry");
        assertTrue(cases.fixed.stream().anyMatch(s -> s.contains("attempts=1;retryQueued=false;deduplicated=0")));
        assertTrue(cases.buggyFailed.stream().allMatch(s -> s.contains("effects=2;attempts=2")));
        assertTrue(cases.buggyPassed.stream().allMatch(s -> s.contains("effects=1;attempts=1")));
    }

    @Test void cancellationOfAnOldAttemptCannotCancelItsUsefulReplacement() {
        var cases = explore("cancel-retry", "retry-survives-stale-cancel");
        assertTrue(cases.fixed.stream().allMatch(s -> s.equals(
                "result=retry-completed;effects=1;retryStarted=true;originalEffects=0")));
        assertTrue(cases.buggyFailed.stream().allMatch(s -> s.equals(
                "result=retry-pending;effects=0;retryStarted=true;originalEffects=0")));
        assertTrue(cases.buggyPassed.stream().allMatch(s -> s.startsWith("result=retry-completed;effects=1;")));
    }

    @Test void closeGuardDiscardsLateCallbacksButAllowsWritesWhileTheResourceIsOpen() {
        var cases = explore("callback-after-close", "no-write-after-close");
        assertTrue(cases.fixed.stream().anyMatch(s -> s.equals(
                "result=written;effects=1;closed=true;writesAfterClose=0")));
        assertTrue(cases.fixed.stream().anyMatch(s -> s.equals(
                "result=discarded-closed;effects=0;closed=true;writesAfterClose=0")));
        assertTrue(cases.buggyFailed.stream().allMatch(s -> s.contains("effects=1;closed=true;writesAfterClose=1")));
        assertTrue(cases.buggyPassed.stream().allMatch(s -> s.contains("effects=1;closed=true;writesAfterClose=0")));
    }

    private static Cases explore(String id, String expectedInvariant) {
        var cases = new Cases();
        for (int seed = 0; seed < 32; seed++) {
            var buggy = run(id, seed, false);
            if (buggy.signature.isEmpty()) cases.buggyPassed.add(buggy.state);
            else {
                assertEquals(id + ":" + expectedInvariant, buggy.signature);
                cases.buggyFailed.add(buggy.state);
            }
            var fixed = run(id, seed, true);
            assertEquals("", fixed.signature, "fixed " + id + " seed=" + seed + " " + fixed.state);
            cases.fixed.add(fixed.state);
        }
        assertFalse(cases.buggyPassed.isEmpty(), "a buggy scenario must retain a successful ordering: " + id);
        assertFalse(cases.buggyFailed.isEmpty(), "the bounded seeds must expose the defect: " + id);
        assertEquals(32, cases.buggyPassed.size() + cases.buggyFailed.size());
        return cases;
    }

    private static Outcome run(String id, int seed, boolean fixed) {
        try (var runtime = new ControlledRuntime(seed)) {
            var observed = ScenarioCorpus.install(id, runtime, fixed);
            String signature = "";
            try { runtime.drain(100); }
            catch (InvariantViolation expected) { signature = expected.signature(); }
            assertEquals(0, runtime.pendingTasks(), "terminal oracle must leave no callbacks behind");
            var steps = runtime.trace().steps();
            assertEquals(id + ":check", steps.get(steps.size() - 1).label());
            if (signature.isEmpty()) assertDoesNotThrow(observed::assertInvariant);
            else assertEquals(signature, assertThrows(InvariantViolation.class, observed::assertInvariant).signature());
            assertEquals(observed.state(), observed.state(), "state diagnostics must be deterministic");
            return new Outcome(observed.state(), signature);
        }
    }

    private record Outcome(String state, String signature) { }
    private static final class Cases {
        final List<String> buggyPassed = new ArrayList<>();
        final List<String> buggyFailed = new ArrayList<>();
        final List<String> fixed = new ArrayList<>();
    }
}
