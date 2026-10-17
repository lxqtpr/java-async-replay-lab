package com.example.deterministicasync.demo.cleanup;

import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.cleanup.CleanupAction;
import com.example.deterministicasync.extension.context.TestContext;

import java.time.Duration;

public final class AwaitIdleOrderServiceCleanupAction implements CleanupAction {

    @Override
    public void cleanup(TestContext context) {
        context.resources()
                .findDependency(OrderService.class)
                .ifPresent(orderService -> context.awaiter()
                        .await()
                        .atMost(context.config().resourceReadyTimeout())
                        .pollInterval(Duration.ofMillis(10))
                        .until(() -> orderService.inFlightCount() == 0));
    }
}
