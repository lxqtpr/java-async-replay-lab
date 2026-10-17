package dev.asyncreplay.example;

import dev.asyncreplay.ControlledRuntime;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Logical timeout/completion race; no real network request or background thread is used. */
public final class TimeoutRaceScenario {
    private TimeoutRaceScenario() { }

    public static Observation install(ControlledRuntime runtime, boolean guarded) {
        var observed = new Observation();
        runtime.schedule("timeout", Duration.ofSeconds(1), () -> observed.result.complete("timeout"));
        runtime.schedule("completion", Duration.ofSeconds(1), () -> {
            if (guarded) {
                if (observed.result.complete("completed")) observed.effects++;
            } else {
                observed.effects++; // bug: publishes a logical side effect even after timeout won
                observed.result.complete("completed");
            }
        });
        runtime.schedule("check-no-late-effect", Duration.ofSeconds(2), observed::assertInvariant);
        return observed;
    }

    public static final class Observation {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private int effects;
        public String result() { return result.getNow("pending"); }
        public int effects() { return effects; }
        public void assertInvariant() {
            if (result().equals("timeout") && effects != 0) throw new LateEffectException();
            if (result().equals("completed") && effects != 1) throw new AssertionError("completion requires one effect");
            if (!result.isDone()) throw new AssertionError("operation has no terminal result");
        }
    }

    public static final class LateEffectException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public LateEffectException() { super("timeout won, but a late completion published an effect"); }
    }
}
