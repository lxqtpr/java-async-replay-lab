package com.example.deterministicasync.demo.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
        UUID id,
        String customerId,
        BigDecimal amount,
        OrderStatus status,
        Instant createdAt,
        Instant processedAt
) {
}
