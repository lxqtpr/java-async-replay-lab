package com.example.deterministicasync.demo;

import com.example.deterministicasync.demo.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

class AwaitilityBaselineTest extends AbstractManualOrderServiceTest {

    @Test
    void awaitilityBasedAssertionWaitsForCompletion() {
        UUID orderId = orderService.createOrder("customer-awaitility", new BigDecimal("110.50"));

        await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(25))
                .ignoreExceptions()
                .untilAsserted(() -> assertEquals(
                        OrderStatus.COMPLETED,
                        orderService.findStatus(orderId).orElseThrow(() -> new IllegalStateException("order not visible yet"))
                ));
    }
}
