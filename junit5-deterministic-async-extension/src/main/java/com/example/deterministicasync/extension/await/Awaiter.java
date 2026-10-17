package com.example.deterministicasync.extension.await;

import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class Awaiter {
    private final DeterministicAsyncSettings settings;

    public Awaiter(DeterministicAsyncSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Probe await() {
        return new Probe(
                settings.defaultTimeout(),
                settings.defaultPollInterval(),
                settings.ignoreConditionExceptions()
        );
    }

    public static final class Probe {
        private Duration timeout;
        private Duration pollInterval;
        private boolean ignoreConditionExceptions;

        private Probe(Duration timeout, Duration pollInterval, boolean ignoreConditionExceptions) {
            this.timeout = timeout;
            this.pollInterval = pollInterval;
            this.ignoreConditionExceptions = ignoreConditionExceptions;
        }

        public Probe atMost(Duration timeout) {
            this.timeout = requirePositive(timeout, "timeout");
            return this;
        }

        public Probe pollInterval(Duration pollInterval) {
            this.pollInterval = requirePositive(pollInterval, "pollInterval");
            return this;
        }

        public Probe ignoreConditionExceptions(boolean ignoreConditionExceptions) {
            this.ignoreConditionExceptions = ignoreConditionExceptions;
            return this;
        }

        public void until(BooleanSupplier condition) {
            until(condition::getAsBoolean, Boolean.TRUE::equals);
        }

        public <T> T until(Supplier<T> probe, Predicate<T> successPredicate) {
            Objects.requireNonNull(probe, "probe must not be null");
            Objects.requireNonNull(successPredicate, "successPredicate must not be null");

            long startNanos = System.nanoTime();
            long timeoutNanos = timeout.toNanos();
            long pollIntervalNanos = pollInterval.toNanos();

            long attempts = 0;
            Object lastObservation = null;
            Throwable lastException = null;

            while (true) {
                attempts++;
                try {
                    T observation = probe.get();
                    lastObservation = observation;
                    if (successPredicate.test(observation)) {
                        return observation;
                    }
                } catch (Throwable throwable) {
                    lastException = throwable;
                    if (!ignoreConditionExceptions) {
                        throw new IllegalStateException("Condition evaluation failed on attempt " + attempts, throwable);
                    }
                }

                long elapsedNanos = System.nanoTime() - startNanos;
                if (elapsedNanos >= timeoutNanos) {
                    throw timeoutException(attempts, elapsedNanos, timeoutNanos, lastObservation, lastException);
                }

                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted while awaiting condition");
                }

                LockSupport.parkNanos(pollIntervalNanos);
            }
        }

        private AwaitTimeoutException timeoutException(
                long attempts,
                long elapsedNanos,
                long timeoutNanos,
                Object lastObservation,
                Throwable lastException
        ) {
            StringBuilder message = new StringBuilder()
                    .append("Await timeout after ")
                    .append(TimeUnit.NANOSECONDS.toMillis(elapsedNanos))
                    .append(" ms (limit=")
                    .append(TimeUnit.NANOSECONDS.toMillis(timeoutNanos))
                    .append(" ms, attempts=")
                    .append(attempts)
                    .append("). Last observation: ")
                    .append(lastObservation == null ? "<none>" : lastObservation);

            if (lastException != null) {
                message.append(". Last condition exception: ")
                        .append(lastException.getClass().getSimpleName())
                        .append(": ")
                        .append(lastException.getMessage());
            }

            AwaitTimeoutException ex = new AwaitTimeoutException(message.toString());
            if (lastException != null) {
                ex.addSuppressed(lastException);
            }
            return ex;
        }

        private Duration requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
