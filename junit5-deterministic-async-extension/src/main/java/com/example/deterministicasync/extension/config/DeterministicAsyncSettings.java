package com.example.deterministicasync.extension.config;

import java.time.Duration;
import java.util.Objects;

public final class DeterministicAsyncSettings {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);
    public static final Duration DEFAULT_RESOURCE_READY_TIMEOUT = Duration.ofSeconds(5);

    private final Duration defaultTimeout;
    private final Duration defaultPollInterval;
    private final Duration resourceReadyTimeout;
    private final boolean ignoreConditionExceptions;

    public DeterministicAsyncSettings(
            Duration defaultTimeout,
            Duration defaultPollInterval,
            Duration resourceReadyTimeout,
            boolean ignoreConditionExceptions
    ) {
        this.defaultTimeout = requirePositive(defaultTimeout, "defaultTimeout");
        this.defaultPollInterval = requirePositive(defaultPollInterval, "defaultPollInterval");
        this.resourceReadyTimeout = requirePositive(resourceReadyTimeout, "resourceReadyTimeout");
        this.ignoreConditionExceptions = ignoreConditionExceptions;
    }

    public static DeterministicAsyncSettings defaults() {
        return new DeterministicAsyncSettings(
                DEFAULT_TIMEOUT,
                DEFAULT_POLL_INTERVAL,
                DEFAULT_RESOURCE_READY_TIMEOUT,
                true
        );
    }

    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    public Duration defaultPollInterval() {
        return defaultPollInterval;
    }

    public Duration resourceReadyTimeout() {
        return resourceReadyTimeout;
    }

    public boolean ignoreConditionExceptions() {
        return ignoreConditionExceptions;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }
}
