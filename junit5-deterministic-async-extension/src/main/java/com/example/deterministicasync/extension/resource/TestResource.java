package com.example.deterministicasync.extension.resource;

public interface TestResource {
    void start() throws Exception;

    void stop() throws Exception;

    boolean isReady();

    String describe();

    default ResourceScope scope() {
        return ResourceScope.PER_CLASS;
    }
}
