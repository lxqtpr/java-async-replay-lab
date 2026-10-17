package com.example.deterministicasync.extension.resource;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ManagedExecutorResource implements TestResource {
    private final int threads;
    private final String threadPrefix;
    private final ResourceScope scope;

    private ExecutorService executor;

    public ManagedExecutorResource(int threads, String threadPrefix, ResourceScope scope) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive");
        }
        this.threads = threads;
        this.threadPrefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newFixedThreadPool(threads, new NamedThreadFactory(threadPrefix));
    }

    @Override
    public void stop() throws InterruptedException {
        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Executor did not terminate within timeout");
        }
    }

    @Override
    public boolean isReady() {
        return executor != null && !executor.isShutdown();
    }

    @Override
    public String describe() {
        return "ManagedExecutorResource{threads=" + threads + ", scope=" + scope + '}';
    }

    @Override
    public ResourceScope scope() {
        return scope;
    }

    public ExecutorService executor() {
        if (executor == null) {
            throw new IllegalStateException("Executor is not started");
        }
        return executor;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + '-' + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
