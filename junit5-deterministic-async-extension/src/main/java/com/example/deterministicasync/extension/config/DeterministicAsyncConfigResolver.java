package com.example.deterministicasync.extension.config;

import com.example.deterministicasync.extension.annotation.DeterministicAsyncTest;

import java.time.Duration;

public final class DeterministicAsyncConfigResolver {
    public static final String PROP_TIMEOUT_MS = "deterministic.async.timeout.ms";
    public static final String PROP_POLL_INTERVAL_MS = "deterministic.async.poll.ms";
    public static final String PROP_RESOURCE_READY_TIMEOUT_MS = "deterministic.async.resource.ready.timeout.ms";
    public static final String PROP_IGNORE_CONDITION_EXCEPTIONS = "deterministic.async.ignore.condition.exceptions";

    private DeterministicAsyncConfigResolver() {
    }

    public static DeterministicAsyncSettings resolve(DeterministicAsyncTest annotation) {
        DeterministicAsyncSettings defaults = DeterministicAsyncSettings.defaults();

        long timeoutMs = resolveLong(
                PROP_TIMEOUT_MS,
                annotation != null ? annotation.timeoutMillis() : defaults.defaultTimeout().toMillis()
        );
        long pollMs = resolveLong(
                PROP_POLL_INTERVAL_MS,
                annotation != null ? annotation.pollIntervalMillis() : defaults.defaultPollInterval().toMillis()
        );
        long resourceTimeoutMs = resolveLong(
                PROP_RESOURCE_READY_TIMEOUT_MS,
                annotation != null ? annotation.resourceReadyTimeoutMillis() : defaults.resourceReadyTimeout().toMillis()
        );
        boolean ignoreExceptions = resolveBoolean(
                PROP_IGNORE_CONDITION_EXCEPTIONS,
                annotation != null ? annotation.ignoreConditionExceptions() : defaults.ignoreConditionExceptions()
        );

        return new DeterministicAsyncSettings(
                Duration.ofMillis(timeoutMs),
                Duration.ofMillis(pollMs),
                Duration.ofMillis(resourceTimeoutMs),
                ignoreExceptions
        );
    }

    private static long resolveLong(String key, long fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Property " + key + " must be > 0, but was " + parsed);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Property " + key + " must be numeric, but was '" + value + "'", ex);
        }
    }

    private static boolean resolveBoolean(String key, boolean fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }
}
