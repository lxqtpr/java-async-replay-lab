package dev.asyncreplay.junit5;

import dev.asyncreplay.ControlledRuntime;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

@AsyncReplayTest(seed = 42)
class AsyncReplayExtensionTest {
    @Test void injectsRuntimeWithExplicitClockAndExecutor(ControlledRuntime runtime) {
        var result = CompletableFuture.supplyAsync(runtime::instant, runtime);
        assertFalse(result.isDone());
        runtime.advanceBy(Duration.ZERO, 1);
        assertEquals(Instant.EPOCH, result.join());
        assertEquals(42L, runtime.trace().seed());
    }

    @Test @AsyncReplayTest(seed = 7)
    void methodOptionsOverrideClassOptions(ControlledRuntime runtime) {
        assertEquals(7L, runtime.trace().seed());
        assertEquals(0, runtime.pendingTasks());
    }

    @Test @AsyncReplayTest(seed = 999, replayFile = "src/test/resources/replay-one.trace")
    void replayFileSelectsRecordedSeedAndConsumesAllDecisions(ControlledRuntime runtime) {
        assertEquals(12L, runtime.trace().seed());
        runtime.execute(() -> { });
        runtime.drain(1);
        runtime.verifyReplayComplete();
    }
}
