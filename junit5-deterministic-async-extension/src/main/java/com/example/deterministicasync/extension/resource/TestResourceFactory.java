package com.example.deterministicasync.extension.resource;

import com.example.deterministicasync.extension.context.TestContext;

import java.util.List;

@FunctionalInterface
public interface TestResourceFactory {
    List<TestResource> createResources(TestContext context) throws Exception;
}
