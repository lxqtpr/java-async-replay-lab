package dev.asyncreplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Portable text format v1. Replay checks task identity, eligible sets, times, and callback result types. */
public final class TraceFiles {
    private TraceFiles() { }

    public static void write(Path path, RunTrace trace) throws IOException {
        StringBuilder text = new StringBuilder("ASYNC_REPLAY\t1\t").append(trace.seed())
                .append('\t').append(trace.origin()).append('\n');
        for (RunTrace.Step step : trace.steps()) {
            text.append(step.timeNanos()).append('\t').append(step.taskId()).append('\t').append(step.label())
                    .append('\t').append(step.eligibleIds().stream().map(Object::toString).collect(Collectors.joining(",")))
                    .append('\t').append(step.outcome()).append('\n');
        }
        if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    public static RunTrace read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        try {
            if (lines.isEmpty()) throw new IllegalArgumentException("empty trace");
            String[] header = lines.get(0).split("\t", -1);
            if (header.length != 4 || !header[0].equals("ASYNC_REPLAY") || !header[1].equals("1")) {
                throw new IllegalArgumentException("unsupported trace header");
            }
            List<RunTrace.Step> steps = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] p = lines.get(i).split("\t", -1);
                if (p.length != 5 || p[2].isBlank() || p[4].isBlank()) throw new IllegalArgumentException("malformed step " + i);
                List<Long> eligible = p[3].isEmpty() ? List.of() : Arrays.stream(p[3].split(",")).map(Long::parseLong).toList();
                long time = Long.parseLong(p[0]);
                long id = Long.parseLong(p[1]);
                if (time < 0 || id <= 0 || !eligible.contains(id)) throw new IllegalArgumentException("invalid step " + i);
                steps.add(new RunTrace.Step(time, id, p[2], eligible, p[4]));
            }
            return new RunTrace(Long.parseLong(header[2]), Instant.parse(header[3]), steps);
        } catch (RuntimeException failure) {
            throw new IOException("invalid async replay trace: " + path, failure);
        }
    }
}
