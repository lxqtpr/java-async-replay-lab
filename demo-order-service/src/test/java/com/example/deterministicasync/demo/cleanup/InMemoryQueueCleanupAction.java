package com.example.deterministicasync.demo.cleanup;

import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.cleanup.CleanupAction;
import com.example.deterministicasync.extension.context.TestContext;

public final class InMemoryQueueCleanupAction implements CleanupAction {
    @Override
    public void cleanup(TestContext context) {
        context.resources()
                .findDependency(OrderService.class)
                .ifPresent(orderService -> {
                    if (orderService.inFlightCount() > 0) {
                        throw new IllegalStateException("Cannot reset in-flight queue before async processing becomes idle");
                    }
                    orderService.resetInFlightQueue();
                });
    }
}
