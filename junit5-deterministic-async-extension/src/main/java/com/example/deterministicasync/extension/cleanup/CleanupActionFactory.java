package com.example.deterministicasync.extension.cleanup;

import com.example.deterministicasync.extension.context.TestContext;

import java.util.List;

@FunctionalInterface
public interface CleanupActionFactory {
    List<CleanupAction> createActions(TestContext context) throws Exception;
}
