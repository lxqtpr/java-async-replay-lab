package com.example.deterministicasync.demo.resource;

import com.example.deterministicasync.extension.context.TestContext;
import com.example.deterministicasync.extension.resource.ManagedExecutorResource;
import com.example.deterministicasync.extension.resource.ResourceScope;
import com.example.deterministicasync.extension.resource.TestResource;
import com.example.deterministicasync.extension.resource.TestResourceFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DemoResourceFactory implements TestResourceFactory {
    @Override
    public List<TestResource> createResources(TestContext context) {
        List<TestResource> resources = new ArrayList<>();

        if (context.resources().find(ManagedExecutorResource.class).isEmpty()) {
            resources.add(new ManagedExecutorResource(4, "order-worker", ResourceScope.PER_CLASS));
        }

        if (context.resources().find(PostgresContainerResource.class).isEmpty()) {
            resources.add(new PostgresContainerResource());
        }

        Optional<ManagedExecutorResource> executor = context.resources().find(ManagedExecutorResource.class);
        Optional<PostgresContainerResource> postgres = context.resources().find(PostgresContainerResource.class);

        if (executor.isPresent() && postgres.isPresent()) {
            resources.add(new OrderServiceResource(postgres.get(), executor.get()));
        }

        return resources;
    }
}
