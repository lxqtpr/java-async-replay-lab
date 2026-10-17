package com.example.deterministicasync.extension.context;

import com.example.deterministicasync.extension.await.Awaiter;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;

public interface TestContext {
    Awaiter awaiter();

    ResourceRegistry resources();

    DeterministicAsyncSettings config();
}
