package com.example.deterministicasync.demo.cleanup;

import com.example.deterministicasync.extension.cleanup.CleanupAction;
import com.example.deterministicasync.extension.cleanup.CleanupActionFactory;
import com.example.deterministicasync.extension.context.TestContext;

import java.util.List;

public final class DemoCleanupFactory implements CleanupActionFactory {
    @Override
    public List<CleanupAction> createActions(TestContext context) {
        return List.of(
                new AwaitIdleOrderServiceCleanupAction(),
                new DatabaseCleanupAction(),
                new InMemoryQueueCleanupAction()
        );
    }
}
