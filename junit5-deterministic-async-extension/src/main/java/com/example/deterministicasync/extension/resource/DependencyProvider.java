package com.example.deterministicasync.extension.resource;

import java.util.Optional;

public interface DependencyProvider {
    <T> Optional<T> resolveDependency(Class<T> type);
}
