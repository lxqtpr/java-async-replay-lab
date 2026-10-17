package com.example.deterministicasync.demo;

import com.example.deterministicasync.demo.model.OrderStatus;
import com.example.deterministicasync.demo.persistence.OrderRepository;
import com.example.deterministicasync.demo.resource.DemoDataSourceFactory;
import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.await.Awaiter;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderServiceQueueTest {

    @Test
    void ordersAreQueuedBeforeSingleWorkerProcessesThem() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        OrderRepository orderRepository = new OrderRepository(DemoDataSourceFactory.h2DataSource("orders_queue_test"));
        orderRepository.initSchema();
        orderRepository.deleteAll();

        OrderService orderService = new OrderService(
                orderRepository,
                executorService,
                Duration.ofMillis(250),
                Duration.ofMillis(250),
                1
        );

        try {
            UUID firstOrderId = orderService.createOrder("customer-queue-1", new BigDecimal("10.00"));
            UUID secondOrderId = orderService.createOrder("customer-queue-2", new BigDecimal("20.00"));
            UUID thirdOrderId = orderService.createOrder("customer-queue-3", new BigDecimal("30.00"));

            Awaiter awaiter = new Awaiter(new DeterministicAsyncSettings(
                    Duration.ofSeconds(2),
                    Duration.ofMillis(10),
                    Duration.ofSeconds(2),
                    true
            ));

            OrderStatus firstStatus = awaiter.await()
                    .atMost(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofMillis(10))
                    .until(
                            () -> orderService.findStatus(firstOrderId)
                                    .orElseThrow(() -> new IllegalStateException("first order not found")),
                            status -> status == OrderStatus.PROCESSING
                    );

            assertEquals(OrderStatus.PROCESSING, firstStatus);
            assertEquals(OrderStatus.QUEUED, orderService.findStatus(secondOrderId).orElseThrow());
            assertEquals(OrderStatus.QUEUED, orderService.findStatus(thirdOrderId).orElseThrow());
            assertTrue(orderService.queueDepth() >= 1, "At least one order should remain queued while the first one is processing");

            awaiter.await()
                    .atMost(Duration.ofSeconds(3))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> orderService.inFlightCount() == 0);

            assertEquals(OrderStatus.COMPLETED, orderService.findStatus(firstOrderId).orElseThrow());
            assertEquals(OrderStatus.COMPLETED, orderService.findStatus(secondOrderId).orElseThrow());
            assertEquals(OrderStatus.COMPLETED, orderService.findStatus(thirdOrderId).orElseThrow());
        } finally {
            orderService.stop();
            executorService.shutdownNow();
            executorService.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}
