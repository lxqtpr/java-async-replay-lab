package com.example.deterministicasync.demo;

import com.example.deterministicasync.demo.cleanup.DemoCleanupFactory;
import com.example.deterministicasync.demo.model.OrderStatus;
import com.example.deterministicasync.demo.resource.DemoResourceFactory;
import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.context.TestContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@com.example.deterministicasync.extension.annotation.DeterministicAsyncTest(
        resourceFactories = DemoResourceFactory.class,
        cleanupFactories = DemoCleanupFactory.class,
        timeoutMillis = 3_000,
        pollIntervalMillis = 20
)
public class DeterministicAsyncTest {

    @Test
    public void deterministicAwaitBasedAssertionIsStable(TestContext context, OrderService orderService) {
        UUID orderId = orderService.createOrder("customer-2", new BigDecimal("99.90"));

        OrderStatus completedStatus = context.awaiter().await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(25))
                .until(
                        () -> orderService.findStatus(orderId)
                                .orElseThrow(() -> new IllegalStateException("order not visible yet")),
                        status -> status == OrderStatus.COMPLETED
                );

        assertEquals(OrderStatus.COMPLETED, completedStatus);
    }
}
