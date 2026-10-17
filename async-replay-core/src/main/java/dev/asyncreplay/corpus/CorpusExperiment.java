package dev.asyncreplay.corpus;

import dev.asyncreplay.ControlledRuntime;
import dev.asyncreplay.RunTrace;
import dev.asyncreplay.TraceFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Offline corpus runner. Timings include trace I/O and are diagnostics, not a benchmark. */
public final class CorpusExperiment {
    private static final int STEP_BUDGET = 100;
    private static final Set<String> GENERATED = Set.of(".git", ".idea", "target", "build",
            "artifacts", "output", "outputs", "research-results");
    private static final Set<String> SOURCE_SUFFIXES = Set.of("java", "xml", "sh", "properties");

    private CorpusExperiment() { }

    /** Runs seeds [0, seedCount), refusing to mix a new experiment with existing output. */
    public static void run(Path repositoryRoot, Path outputDirectory, int seedCount) throws IOException {
        if (seedCount <= 0) throw new IllegalArgumentException("seed count must be positive");
        Path root = repositoryRoot.toRealPath();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (root.startsWith(output)) {
            throw new IllegalArgumentException("output directory must not contain the source repository");
        }
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) throw new IllegalArgumentException("output is not a directory: " + output);
            try (var children = Files.list(output)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("output directory is nonempty: " + output);
                }
            }
        }
        Files.createDirectories(output);
        // Resolve any parent symlink before exclusion from the source manifest.
        output = output.toRealPath();
        if (root.startsWith(output)) {
            throw new IllegalArgumentException("output directory must not contain the source repository");
        }
        SourceManifest manifest = sourceManifest(root, output);
        writeEnvironment(output, seedCount, manifest);
        List<String> ids = List.copyOf(ScenarioCorpus.ids());
        if (ids.isEmpty() || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalStateException("scenario IDs must be nonempty and unique");
        }
        for (String id : ids) {
            if (!id.matches("[a-z0-9][a-z0-9-]*")) throw new IllegalStateException("unsafe scenario ID: " + id);
        }
        Map<String, Counts[]> counts = new LinkedHashMap<>();
        for (String id : ids) counts.put(id, new Counts[] { new Counts(), new Counts() });
        long expectedRows = Math.multiplyExact(Math.multiplyExact((long) ids.size(), 2L), seedCount);
        long rows = 0;
        try (BufferedWriter csv = Files.newBufferedWriter(output.resolve("raw.csv"), StandardCharsets.UTF_8)) {
            csv.write("scenario,variant,seed,outcome,failure_signature,state,steps,trace_bytes,replay_match,wall_nanos\n");
            for (String id : ids) {
                for (boolean fixed : new boolean[] { false, true }) {
                    String variant = fixed ? "fixed" : "buggy";
                    Counts stats = counts.get(id)[fixed ? 1 : 0];
                    for (int seed = 0; seed < seedCount; seed++) {
                        long started = System.nanoTime();
                        Path tracePath = output.resolve("traces").resolve(id).resolve(variant)
                                .resolve("seed-" + seed + ".trace");
                        try {
                            Result recorded = execute(id, fixed, new ControlledRuntime(seed));
                            TraceFiles.write(tracePath, recorded.trace());
                            RunTrace persisted = TraceFiles.read(tracePath);
                            require(recorded.trace().equals(persisted), "trace write/read changed decisions");
                            Result replayed = execute(id, fixed, ControlledRuntime.replay(persisted));
                            require(recorded.failureSignature().equals(replayed.failureSignature()),
                                    "replay failure signature changed");
                            require(recorded.state().equals(replayed.state()), "replay final state changed");
                            require(recorded.trace().equals(replayed.trace()), "replay trace changed");
                            boolean invariantFailure = !recorded.failureSignature().isEmpty();
                            String outcome = invariantFailure
                                    ? (fixed ? "FIXED_INVARIANT_FAILURE" : "EXPECTED_BUG") : "PASS";
                            csvRecord(csv, List.of(id, variant, Integer.toString(seed), outcome,
                                    recorded.failureSignature(), recorded.state(),
                                    Integer.toString(recorded.trace().steps().size()),
                                    Long.toString(Files.size(tracePath)), "true",
                                    Long.toString(System.nanoTime() - started)));
                            stats.runs++;
                            stats.replayMatches++;
                            if (invariantFailure) stats.invariantFailures++;
                            else stats.passes++;
                            rows++;
                        } catch (IOException | RuntimeException | Error failure) {
                            stats.unexpectedErrors++;
                            try {
                                csv.flush();
                                writeSummary(output, counts, seedCount, rows, expectedRows, "ERROR",
                                        List.of(id + "/" + variant + "/seed-" + seed + ": "
                                                + failure.getClass().getName() + ": " + failure.getMessage()));
                            } catch (IOException summaryFailure) {
                                failure.addSuppressed(summaryFailure);
                            }
                            throw failure;
                        }
                    }
                }
            }
        }
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Counts[]> entry : counts.entrySet()) {
            if (entry.getValue()[0].invariantFailures == 0) {
                violations.add(entry.getKey() + ": no buggy invariant failure in the selected seed range");
            }
            if (entry.getValue()[1].invariantFailures != 0) {
                violations.add(entry.getKey() + ": fixed variant violated its invariant");
            }
        }
        require(rows == expectedRows, "row count changed");
        writeSummary(output, counts, seedCount, rows, expectedRows,
                violations.isEmpty() ? "PASS" : "FAIL", violations);
        if (!violations.isEmpty()) throw new IllegalStateException("corpus acceptance failed: " + violations);
    }

    private static Result execute(String id, boolean fixed, ControlledRuntime runtime) {
        try (runtime) {
            // Installation failures are setup errors, never expected bug observations.
            ScenarioCorpus.Observation observation = Objects.requireNonNull(
                    ScenarioCorpus.install(id, runtime, fixed), "observation");
            String scheduledSignature = "";
            try {
                runtime.drain(STEP_BUDGET);
            } catch (ScenarioCorpus.InvariantViolation failure) {
                scheduledSignature = signature(failure);
            }
            require(runtime.pendingTasks() == 0, "scenario left pending tasks after its terminal check");
            RunTrace trace = runtime.trace();
            require(!trace.steps().isEmpty()
                            && trace.steps().get(trace.steps().size() - 1).label().equals(id + ":check"),
                    "scenario did not finish at its scheduled terminal check");
            String finalSignature = "";
            try {
                observation.assertInvariant();
            } catch (ScenarioCorpus.InvariantViolation failure) {
                finalSignature = signature(failure);
            }
            require(scheduledSignature.equals(finalSignature), "terminal and final invariant signatures differ");
            runtime.verifyReplayComplete();
            return new Result(trace, scheduledSignature,
                    Objects.requireNonNull(observation.state(), "final state"));
        }
    }

    private static String signature(ScenarioCorpus.InvariantViolation failure) {
        String signature = Objects.requireNonNull(failure.signature(), "failure signature");
        require(!signature.isBlank(), "invariant failure has no stable signature");
        return signature;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void csvRecord(BufferedWriter writer, List<String> cells) throws IOException {
        for (int index = 0; index < cells.size(); index++) {
            if (index != 0) writer.write(',');
            writer.write('"');
            writer.write(cells.get(index).replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write('\n');
    }

    private static void writeSummary(Path output, Map<String, Counts[]> counts, int seedCount,
                                     long rows, long expectedRows, String status,
                                     List<String> violations) throws IOException {
        Counts total = new Counts();
        StringBuilder scenarios = new StringBuilder("[");
        for (Map.Entry<String, Counts[]> entry : counts.entrySet()) {
            if (scenarios.length() > 1) scenarios.append(',');
            scenarios.append("\n    {\"scenario\":").append(json(entry.getKey()))
                    .append(",\"buggy\":").append(entry.getValue()[0].json())
                    .append(",\"fixed\":").append(entry.getValue()[1].json()).append('}');
            for (Counts item : entry.getValue()) total.add(item);
        }
        scenarios.append("\n  ]");
        String text = "{\n  \"schema_version\":1,\n  \"status\":" + json(status)
                + ",\n  \"seed_start\":0,\n  \"seed_end_exclusive\":" + seedCount
                + ",\n  \"rows\":" + rows + ",\n  \"expected_rows\":" + expectedRows
                + ",\n  \"totals\":" + total.json()
                + ",\n  \"acceptance\":{\"passed\":" + status.equals("PASS")
                + ",\"violations\":" + jsonArray(violations) + "}"
                + ",\n  \"per_scenario\":" + scenarios + "\n}\n";
        Files.writeString(output.resolve("summary.json"), text, StandardCharsets.UTF_8);
    }

    private static SourceManifest sourceManifest(Path root, Path output) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.startsWith(output))
                    .filter(path -> isSource(root.relativize(path)))
                    .sorted(Comparator.comparing(path -> relative(root, path))).toList();
        }
        MessageDigest aggregate = sha256();
        List<String> entries = new ArrayList<>();
        for (Path file : files) {
            String relative = relative(root, file);
            byte[] digest = sha256().digest(Files.readAllBytes(file));
            aggregate.update(relative.getBytes(StandardCharsets.UTF_8));
            aggregate.update((byte) 0);
            aggregate.update(digest);
            aggregate.update((byte) 0);
            entries.add("{\"path\":" + json(relative) + ",\"sha256\":"
                    + json(HexFormat.of().formatHex(digest)) + "}");
        }
        require(!entries.isEmpty(), "no source files found under repository root");
        return new SourceManifest(HexFormat.of().formatHex(aggregate.digest()), entries);
    }

    private static boolean isSource(Path relative) {
        for (Path component : relative) if (GENERATED.contains(component.toString())) return false;
        String name = relative.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_SUFFIXES.contains(name.substring(dot + 1));
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void writeEnvironment(Path output, int seeds, SourceManifest manifest) throws IOException {
        String text = "{\n  \"schema_version\":1,\n  \"command\":"
                + jsonArray(List.of("./scripts/run_corpus.sh", output.toString(), Integer.toString(seeds)))
                + ",\n  \"java\":{\"version\":" + json(System.getProperty("java.version"))
                + ",\"runtime_version\":" + json(System.getProperty("java.runtime.version"))
                + ",\"vendor\":" + json(System.getProperty("java.vendor")) + "}"
                + ",\n  \"os\":{\"name\":" + json(System.getProperty("os.name"))
                + ",\"version\":" + json(System.getProperty("os.version"))
                + ",\"arch\":" + json(System.getProperty("os.arch")) + "}"
                + ",\n  \"seed_start\":0,\n  \"seed_end_exclusive\":" + seeds
                + ",\n  \"step_budget\":" + STEP_BUDGET
                + ",\n  \"wall_nanos_scope\":" + json("record, trace write/read and replay; diagnostic only, not a benchmark")
                + ",\n  \"source_sha256\":" + json(manifest.sha256())
                + ",\n  \"source_hash_scope\":" + json("Java sources, XML build descriptors, shell scripts and properties only; no docs, reports or datasets")
                + ",\n  \"source_hash_format\":" + json("sorted relative UTF-8 path, NUL, binary file SHA-256, NUL")
                + ",\n  \"source_extensions\":" + jsonArray(SOURCE_SUFFIXES.stream().sorted().toList())
                + ",\n  \"compile_release\":17"
                + ",\n  \"excluded_directories\":" + jsonArray(GENERATED.stream().sorted().toList())
                + ",\n  \"source_files\":[\n    " + String.join(",\n    ", manifest.entries()) + "\n  ]\n}\n";
        Files.writeString(output.resolve("environment.json"), text, StandardCharsets.UTF_8);
    }

    private static String jsonArray(List<String> values) {
        return "[" + String.join(",", values.stream().map(CorpusExperiment::json).toList()) + "]";
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2) {
                throw new IllegalArgumentException("Usage: ./scripts/run_corpus.sh <output-dir> [seed-count=32]");
            }
            int seeds = args.length == 2 ? Integer.parseInt(args[1]) : 32;
            Path root = Path.of(System.getProperty("asyncreplay.repoRoot", "."));
            run(root, Path.of(args[0]), seeds);
            System.out.println("CORPUS COMPLETE: strict replay matched; see " + args[0] + "/summary.json");
        } catch (IOException | RuntimeException | Error failure) {
            System.err.println("CORPUS ERROR: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private record Result(RunTrace trace, String failureSignature, String state) { }
    private record SourceManifest(String sha256, List<String> entries) { }

    private static final class Counts {
        private long runs;
        private long passes;
        private long invariantFailures;
        private long replayMatches;
        private long unexpectedErrors;

        private void add(Counts other) {
            runs += other.runs;
            passes += other.passes;
            invariantFailures += other.invariantFailures;
            replayMatches += other.replayMatches;
            unexpectedErrors += other.unexpectedErrors;
        }

        private String json() {
            return "{\"runs\":" + runs + ",\"passes\":" + passes
                    + ",\"invariant_failures\":" + invariantFailures
                    + ",\"replay_matches\":" + replayMatches
                    + ",\"unexpected_errors\":" + unexpectedErrors + "}";
        }
    }
}
