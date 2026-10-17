package com.example.deterministicasync.demo;

import com.example.deterministicasync.demo.cleanup.DemoCleanupFactory;
import com.example.deterministicasync.demo.model.OrderStatus;
import com.example.deterministicasync.demo.persistence.OrderRepository;
import com.example.deterministicasync.demo.resource.DemoResourceFactory;
import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.context.TestContext;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@com.example.deterministicasync.extension.annotation.DeterministicAsyncTest(
        resourceFactories = DemoResourceFactory.class,
        cleanupFactories = DemoCleanupFactory.class,
        timeoutMillis = 3_000,
        pollIntervalMillis = 20
)
public class DeterministicIsolationRegressionTest {

    @Test
    @Order(1)
    void firstExtensionBasedTestLeavesDataOnlyInsideItsOwnLifecycle(
            TestContext context,
            OrderService orderService,
            OrderRepository orderRepository
    ) {
        assertEquals(0L, orderRepository.countOrders());

        UUID orderId = orderService.createOrder("customer-first", new BigDecimal("15.75"));

        OrderStatus status = context.awaiter().await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(25))
                .until(
                        () -> orderService.findStatus(orderId)
                                .orElseThrow(() -> new IllegalStateException("order not visible yet")),
                        currentStatus -> currentStatus == OrderStatus.COMPLETED
                );

        context.awaiter()
                .await()
                .atMost(context.config().resourceReadyTimeout())
                .pollInterval(Duration.ofMillis(10))
                .until(() -> orderService.inFlightCount() == 0);

        assertEquals(OrderStatus.COMPLETED, status);
        assertEquals(1L, orderRepository.countOrders());
        assertEquals(0, orderService.inFlightCount());
    }

    @Test
    @Order(2)
    void secondExtensionBasedTestStartsFromCleanState(OrderService orderService, OrderRepository orderRepository) {
        assertEquals(0L, orderRepository.countOrders());
        assertEquals(0, orderService.inFlightCount());
    }
}
