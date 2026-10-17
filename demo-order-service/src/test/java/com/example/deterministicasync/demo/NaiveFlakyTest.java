package com.example.deterministicasync.demo;

import com.example.deterministicasync.demo.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NaiveFlakyTest extends AbstractManualOrderServiceTest {

    @Test
    public void naiveSleepBasedAssertionCanBeFlaky() throws Exception {
        UUID orderId = orderService.createOrder("customer-1", new BigDecimal("120.00"));

        Thread.sleep(1200);

        OrderStatus actualStatus = orderService
                .findStatus(orderId)
                .orElse(OrderStatus.QUEUED);

        assertEquals(
                OrderStatus.COMPLETED,
                actualStatus,
                "Наивный sleep-подход иногда проверяет статус слишком рано"
        );
    }
}
