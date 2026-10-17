package com.example.deterministicasync.demo.cleanup;

import com.example.deterministicasync.demo.persistence.OrderRepository;
import com.example.deterministicasync.extension.cleanup.CleanupAction;
import com.example.deterministicasync.extension.context.TestContext;

public final class DatabaseCleanupAction implements CleanupAction {
    @Override
    public void cleanup(TestContext context) {
        context.resources()
                .findDependency(OrderRepository.class)
                .ifPresent(OrderRepository::deleteAll);
    }
}
