package dev.asyncreplay.example;

import dev.asyncreplay.ControlledRuntime;
import dev.asyncreplay.TraceFiles;
import java.nio.file.Path;

/** Run via scripts/demo.sh. Expected bug findings are printed, replay divergence is an error. */
public final class TimeoutRaceDemo {
    private TimeoutRaceDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("record <seed> <trace> [guarded] | replay <trace> [guarded]");
        boolean record = args[0].equals("record");
        if (!record && !args[0].equals("replay")) throw new IllegalArgumentException("expected record or replay");
        if ((record && args.length < 3) || args.length > (record ? 4 : 3)) throw new IllegalArgumentException("invalid arguments");
        int flagIndex = record ? 3 : 2;
        boolean guarded = args.length > flagIndex;
        if (guarded && !args[flagIndex].equals("guarded")) throw new IllegalArgumentException("optional argument is guarded");
        Path path = Path.of(args[record ? 2 : 1]);
        try (var runtime = record ? new ControlledRuntime(Long.parseLong(args[1])) : ControlledRuntime.replay(TraceFiles.read(path))) {
            var observed = TimeoutRaceScenario.install(runtime, guarded);
            String finding = "invariant satisfied";
            try { runtime.drain(10); }
            catch (TimeoutRaceScenario.LateEffectException failure) { finding = "BUG REPRODUCED: " + failure.getMessage(); }
            if (record) TraceFiles.write(path, runtime.trace());
            runtime.verifyReplayComplete();
            System.out.printf("seed=%d, guarded=%s, result=%s, effects=%d, steps=%d%n%s%n",
                    runtime.trace().seed(), guarded, observed.result(), observed.effects(), runtime.trace().steps().size(), finding);
        }
    }
}
