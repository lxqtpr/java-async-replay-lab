package dev.asyncreplay;

import org.junit.jupiter.api.Test;
import dev.asyncreplay.example.TimeoutRaceScenario;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ControlledRuntimeTest {
    @Test void advancesVirtualTimeAndRunsNestedTasksAtTheirDeadlines() {
        try (var runtime = new ControlledRuntime(7)) {
            List<String> observed = new ArrayList<>();
            runtime.schedule("late", Duration.ofDays(1), () -> observed.add("late"));
            runtime.schedule("first", Duration.ofSeconds(1), () -> {
                observed.add("first");
                runtime.schedule("child", Duration.ofSeconds(1), () -> observed.add("child"));
            });
            runtime.advanceBy(Duration.ofSeconds(2), 10);
            assertEquals(List.of("first", "child"), observed);
            assertEquals(Instant.EPOCH.plusSeconds(2), runtime.instant());
            assertEquals(runtime.instant(), runtime.withZone(ZoneId.of("Europe/Paris")).instant());
            runtime.drain(10);
            assertEquals(Instant.EPOCH.plus(Duration.ofDays(1)), runtime.instant());
        }
    }

    @Test void explicitExecutorControlsCompletableFutureChain() {
        try (var runtime = new ControlledRuntime(2)) {
            var future = CompletableFuture.supplyAsync(() -> 20, runtime).thenApplyAsync(v -> v + 22, runtime);
            assertFalse(future.isDone());
            runtime.drain(10);
            assertEquals(42, future.join());
            assertEquals(2, runtime.trace().steps().size());
        }
    }

    @Test void sameSeedAndScenarioProduceIdenticalTrace() {
        assertEquals(recordRace(27), recordRace(27));
    }

    @Test void savedTraceReplaysSameRaceAndOutcome() throws Exception {
        RunTrace trace = recordRace(17);
        var file = Files.createTempFile("async-replay-", ".trace");
        try {
            TraceFiles.write(file, trace);
            RunTrace restored = TraceFiles.read(file);
            assertEquals(trace, restored);
            try (var replay = ControlledRuntime.replay(restored)) {
                installRace(replay);
                replay.drain(10);
                assertEquals(trace, replay.trace());
                replay.verifyReplayComplete();
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test void severalSeedsExposeBothOrdersOfSameDeadlineTasks() {
        List<Long> firstIds = new ArrayList<>();
        for (int seed = 0; seed < 30; seed++) firstIds.add(recordRace(seed).steps().get(0).taskId());
        assertTrue(firstIds.contains(1L));
        assertTrue(firstIds.contains(2L));
    }

    @Test void replayRejectsChangedEligibleSetBeforeExecutingAnything() {
        var replay = ControlledRuntime.replay(recordRace(9));
        installRace(replay);
        replay.execute(() -> fail("unexpected task must not run"));
        assertThrows(ReplayMismatchException.class, replay::runNext);
        assertThrows(IllegalStateException.class, replay::close); // pending tasks are reported
    }

    @Test void replayRejectsChangedLabelAndChangedCallbackOutcome() {
        RunTrace trace;
        try (var runtime = new ControlledRuntime(0)) {
            runtime.execute(() -> { });
            runtime.drain(1);
            trace = runtime.trace();
        }
        var renamed = ControlledRuntime.replay(trace);
        renamed.schedule("changed", Duration.ZERO, () -> { });
        assertThrows(ReplayMismatchException.class, renamed::runNext);
        assertThrows(IllegalStateException.class, renamed::close);
        try (var changed = ControlledRuntime.replay(trace)) {
            changed.execute(() -> { throw new IllegalArgumentException("new bug"); });
            assertThrows(ReplayMismatchException.class, changed::runNext);
        }
    }

    @Test void callbackFailureIsRecordedAndCanBeReplayed() {
        RunTrace trace;
        try (var runtime = new ControlledRuntime(1)) {
            runtime.execute(() -> { throw new IllegalStateException("original bug"); });
            assertThrows(IllegalStateException.class, runtime::runNext);
            trace = runtime.trace();
            assertEquals(IllegalStateException.class.getName(), trace.steps().get(0).outcome());
        }
        try (var replay = ControlledRuntime.replay(trace)) {
            replay.execute(() -> { throw new IllegalStateException("original bug"); });
            assertThrows(IllegalStateException.class, replay::runNext);
            assertEquals(trace, replay.trace());
        }
    }

    @Test void cancellationPreventsEffectAndCanOnlySucceedOnce() {
        try (var runtime = new ControlledRuntime(0)) {
            var task = runtime.schedule("cancelled", Duration.ZERO, () -> fail("cancelled task ran"));
            assertTrue(task.cancel());
            assertFalse(task.cancel());
            runtime.drain(0);
            assertTrue(runtime.trace().steps().isEmpty());
        }
    }

    @Test void closeDetectsTaskLeakAndFurtherSubmissionsAreRejected() {
        var runtime = new ControlledRuntime(0);
        runtime.execute(() -> { });
        assertTrue(assertThrows(IllegalStateException.class, runtime::close).getMessage().contains("leak"));
        assertThrows(IllegalStateException.class, () -> runtime.execute(() -> { }));
        runtime.close();
    }

    @Test void replayMustConsumeAllRecordedDecisions() {
        var runtime = ControlledRuntime.replay(recordRace(0));
        assertThrows(ReplayMismatchException.class, runtime::close);
    }

    @Test void stepBudgetBoundsImmediateSelfRescheduling() {
        var runtime = new ControlledRuntime(0);
        Runnable loop = new Runnable() { public void run() { runtime.execute(this); } };
        runtime.execute(loop);
        assertTrue(assertThrows(IllegalStateException.class, () -> runtime.drain(4)).getMessage().contains("budget"));
        assertEquals(4, runtime.trace().steps().size());
        assertThrows(IllegalStateException.class, runtime::close);
    }

    @Test void externalThreadAndNestedDrivingAreRejected() throws Exception {
        try (var runtime = new ControlledRuntime(0)) {
            var failure = new AtomicReference<Throwable>();
            Thread thread = new Thread(() -> {
                try { runtime.execute(() -> { }); } catch (Throwable caught) { failure.set(caught); }
            });
            thread.start(); thread.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
            runtime.execute(() -> {
                assertThrows(IllegalStateException.class, runtime::runNext);
                assertThrows(IllegalStateException.class, () -> runtime.drain(0));
                assertThrows(IllegalStateException.class, () -> runtime.advanceBy(Duration.ZERO, 0));
            });
            runtime.drain(1);
        }
    }

    @Test void negativeDurationAndDeadlineOverflowAreRejected() {
        try (var runtime = new ControlledRuntime(0)) {
            assertThrows(IllegalArgumentException.class, () -> runtime.schedule("bad", Duration.ofNanos(-1), () -> { }));
            runtime.advanceBy(Duration.ofNanos(Long.MAX_VALUE), 0);
            assertThrows(ArithmeticException.class, () -> runtime.schedule("overflow", Duration.ofNanos(1), () -> { }));
        }
    }

    @Test void invalidTraceVersionIsRejected() throws Exception {
        var file = Files.createTempFile("async-replay-invalid-", ".trace");
        try {
            Files.writeString(file, "ASYNC_REPLAY\t99\t0\t1970-01-01T00:00:00Z\n");
            assertThrows(java.io.IOException.class, () -> TraceFiles.read(file));
        } finally { Files.deleteIfExists(file); }
    }

    @Test void malformedTimestampIsReportedAsInvalidTrace() throws Exception {
        var file = Files.createTempFile("async-replay-invalid-time-", ".trace");
        try {
            Files.writeString(file, "ASYNC_REPLAY\t1\t0\tnot-an-instant\n");
            assertThrows(java.io.IOException.class, () -> TraceFiles.read(file));
        } finally { Files.deleteIfExists(file); }
    }

    @Test void lateEffectCounterexampleCanBeReplayedAndGuardedCompletionSurvivesBothOrders() {
        RunTrace failingTrace = null;
        for (int seed = 0; seed < 30; seed++) {
            try (var runtime = new ControlledRuntime(seed)) {
                var observed = TimeoutRaceScenario.install(runtime, false);
                try { runtime.drain(10); }
                catch (TimeoutRaceScenario.LateEffectException expected) {
                    assertEquals("timeout", observed.result());
                    assertEquals(1, observed.effects());
                    if (failingTrace == null) failingTrace = runtime.trace();
                }
            }
            try (var runtime = new ControlledRuntime(seed)) {
                var observed = TimeoutRaceScenario.install(runtime, true);
                runtime.drain(10);
                observed.assertInvariant();
            }
        }
        assertNotNull(failingTrace, "bounded seed exploration must expose the counterexample");
        try (var runtime = ControlledRuntime.replay(failingTrace)) {
            var observed = TimeoutRaceScenario.install(runtime, false);
            assertThrows(TimeoutRaceScenario.LateEffectException.class, () -> runtime.drain(10));
            assertEquals("timeout", observed.result());
            assertEquals(1, observed.effects());
            assertEquals(failingTrace, runtime.trace());
        }
    }

    private static RunTrace recordRace(long seed) {
        try (var runtime = new ControlledRuntime(seed)) {
            installRace(runtime);
            runtime.drain(10);
            return runtime.trace();
        }
    }

    private static void installRace(ControlledRuntime runtime) {
        var result = new CompletableFuture<String>();
        runtime.schedule("timeout", Duration.ofSeconds(1), () -> result.complete("timeout"));
        runtime.schedule("completion", Duration.ofSeconds(1), () -> result.complete("completed"));
        runtime.schedule("assert-terminal", Duration.ofSeconds(2), () -> assertTrue(result.isDone()));
    }
}
