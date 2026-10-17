package com.example.deterministicasync.extension.cleanup;

import com.example.deterministicasync.extension.context.TestContext;

@FunctionalInterface
public interface CleanupAction {
    void cleanup(TestContext context) throws Exception;
}
