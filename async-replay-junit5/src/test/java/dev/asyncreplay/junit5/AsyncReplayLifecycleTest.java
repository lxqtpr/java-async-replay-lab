package dev.asyncreplay.junit5;

import dev.asyncreplay.ControlledRuntime;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class AsyncReplayLifecycleTest {
    @Test void aPassingBodyWithLeakedWorkFailsThroughRealJupiterTeardown() {
        var events = EngineTestKit.engine("junit-jupiter").selectors(selectClass(LeakingScenario.class))
                .execute().testEvents();
        assertEquals(1L, events.failed().count());
        var failure = events.failed().list().get(0).getRequiredPayload(TestExecutionResult.class)
                .getThrowable().orElseThrow();
        assertTrue(failure.getMessage().contains("pending task leak"));
    }

    @AsyncReplayTest
    static class LeakingScenario {
        @Test void leavesWorkBehind(ControlledRuntime runtime) { runtime.execute(() -> { }); }
    }
}
