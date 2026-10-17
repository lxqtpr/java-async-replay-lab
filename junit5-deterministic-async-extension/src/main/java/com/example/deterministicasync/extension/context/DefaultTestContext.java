package com.example.deterministicasync.extension.context;

import com.example.deterministicasync.extension.await.Awaiter;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;

public final class DefaultTestContext implements TestContext {
    private final Awaiter awaiter;
    private final ResourceRegistry resources;
    private final DeterministicAsyncSettings settings;

    public DefaultTestContext(DeterministicAsyncSettings settings, ResourceRegistry resources) {
        this.settings = settings;
        this.resources = resources;
        this.awaiter = new Awaiter(settings);
    }

    @Override
    public Awaiter awaiter() {
        return awaiter;
    }

    @Override
    public ResourceRegistry resources() {
        return resources;
    }

    @Override
    public DeterministicAsyncSettings config() {
        return settings;
    }

    public DefaultTestContext withResources(ResourceRegistry newResources) {
        return new DefaultTestContext(settings, newResources);
    }
}
