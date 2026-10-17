package com.example.deterministicasync.extension;

import com.example.deterministicasync.extension.await.AwaitTimeoutException;
import com.example.deterministicasync.extension.await.Awaiter;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwaiterTest {

    @Test
    void untilWaitsUntilConditionIsSatisfied() {
        DeterministicAsyncSettings settings = new DeterministicAsyncSettings(
                Duration.ofSeconds(1),
                Duration.ofMillis(5),
                Duration.ofSeconds(1),
                true
        );

        Awaiter awaiter = new Awaiter(settings);
        AtomicInteger attempts = new AtomicInteger();

        awaiter.await()
                .atMost(Duration.ofMillis(250))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> attempts.incrementAndGet() >= 3);

        assertTrue(attempts.get() >= 3);
    }

    @Test
    void timeoutContainsDiagnosticDetails() {
        DeterministicAsyncSettings settings = DeterministicAsyncSettings.defaults();
        Awaiter awaiter = new Awaiter(settings);

        AwaitTimeoutException ex = assertThrows(AwaitTimeoutException.class, () ->
                awaiter.await()
                        .atMost(Duration.ofMillis(80))
                        .pollInterval(Duration.ofMillis(10))
                        .until(() -> "still-not-ready", observation -> observation.equals("ready"))
        );

        String msg = ex.getMessage();
        assertTrue(msg.contains("attempts="));
        assertTrue(msg.contains("Last observation:"));
    }
}
