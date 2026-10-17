package com.example.deterministicasync.extension.context;

import com.example.deterministicasync.extension.resource.DependencyProvider;
import com.example.deterministicasync.extension.resource.TestResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ResourceRegistry {
    private static final ResourceRegistry EMPTY = new ResourceRegistry(List.of());

    private final List<TestResource> resources;

    private ResourceRegistry(List<TestResource> resources) {
        this.resources = List.copyOf(resources);
    }

    public static ResourceRegistry empty() {
        return EMPTY;
    }

    public static ResourceRegistry of(List<TestResource> resources) {
        return new ResourceRegistry(resources);
    }

    public <T> Optional<T> find(Class<T> type) {
        return resources.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    public <T> Optional<T> findDependency(Class<T> type) {
        Optional<T> directResource = find(type);
        if (directResource.isPresent()) {
            return directResource;
        }

        return resources.stream()
                .filter(DependencyProvider.class::isInstance)
                .map(DependencyProvider.class::cast)
                .map(provider -> provider.resolveDependency(type))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException("Resource not found: " + type.getName()));
    }

    public <T> T requireDependency(Class<T> type) {
        return findDependency(type)
                .orElseThrow(() -> new IllegalStateException("Dependency not found: " + type.getName()));
    }

    public ResourceRegistry merge(List<TestResource> additional) {
        List<TestResource> merged = new ArrayList<>(resources);
        merged.addAll(additional);
        return new ResourceRegistry(merged);
    }
}
