package dev.asyncreplay.reducer;

import dev.asyncreplay.RunTrace;
import dev.asyncreplay.TraceFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Equal-input comparison of two known reduction baselines, with raw evidence. */
public final class ReducerExperiment {
    private static final int BUDGET = 10_000;
    private ReducerExperiment() { }

    public static void run(Path root, Path output) throws IOException {
        Path normalizedRoot = root.toRealPath();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        if (normalizedRoot.startsWith(normalizedOutput)) throw new IllegalArgumentException("unsafe output");
        if (Files.exists(normalizedOutput)) {
            try (var items = Files.list(normalizedOutput)) {
                if (items.findAny().isPresent()) throw new IllegalArgumentException("output must be empty");
            }
        }
        Files.createDirectories(normalizedOutput);
        normalizedOutput = normalizedOutput.toRealPath();
        if (normalizedRoot.startsWith(normalizedOutput)) throw new IllegalArgumentException("unsafe output symlink");
        writeEnvironment(normalizedRoot, normalizedOutput);
        int rows = 0;
        try (BufferedWriter csv = Files.newBufferedWriter(normalizedOutput.resolve("raw.csv"), StandardCharsets.UTF_8)) {
            csv.write("case,mode,input_events,result_events,evaluations,oracle_calls,invalid_candidates,other_failures,cache_hits,one_minimal,budget_exhausted,signature,original_replay,minimized_replay,fixed_control\n");
            for (int noise : new int[] { 4, 16, 64 }) {
                for (int position : new int[] { 0, noise / 2, noise }) {
                    String id = "noise-" + noise + "-position-" + position;
                    List<FailureReducer.Event> original = ReductionWorkload.input(noise, position);
                    for (FailureReducer.Mode mode : FailureReducer.Mode.values()) {
                        FailureReducer.Result result = FailureReducer.reduce(original, mode, BUDGET,
                                input -> ReductionWorkload.record(input, false).outcome());
                        require(result.oneMinimal() && !result.budgetExhausted(), "reduction incomplete");
                        require(result.signature().equals(ReductionWorkload.SIGNATURE), "wrong signature");
                        Path casePath = normalizedOutput.resolve(id).resolve(mode.name().toLowerCase(java.util.Locale.ROOT));
                        Files.createDirectories(casePath);
                        persistInput(casePath.resolve("original-input.tsv"), original);
                        persistInput(casePath.resolve("minimized-input.tsv"), result.events());
                        verifyPersistedReplay(original, casePath.resolve("original.trace"));
                        verifyPersistedReplay(result.events(), casePath.resolve("minimized.trace"));
                        require(ReductionWorkload.record(original, true).outcome().status() == FailureReducer.Status.PASS,
                                "original fixed control failed");
                        require(ReductionWorkload.record(result.events(), true).outcome().status() == FailureReducer.Status.PASS,
                                "minimized fixed control failed");
                        verifyOneMinimal(result.events(), mode);
                        FailureReducer.Statistics stats = result.statistics();
                        csv.write(String.join(",", List.of(id, mode.name(), Integer.toString(original.size()),
                                Integer.toString(result.events().size()), Integer.toString(stats.evaluations()),
                                Integer.toString(stats.oracleCalls()), Integer.toString(stats.structurallyInvalid()),
                                Integer.toString(stats.otherFailures()), Integer.toString(stats.cacheHits()),
                                "true", "false", result.signature(), "true", "true", "PASS")) + "\n");
                        rows++;
                    }
                }
            }
        }
        Files.writeString(normalizedOutput.resolve("summary.json"),
                "{\"status\":\"PASS\",\"rows\":" + rows + ",\"workloads\":9,\"modes\":2,\"budget\":" + BUDGET
                        + ",\"scope\":\"constructed workload reduction, not general runtime trace minimization or performance benchmark\"}\n");
    }

    private static void verifyPersistedReplay(List<FailureReducer.Event> input, Path path) throws IOException {
        ReductionWorkload.Execution original = ReductionWorkload.record(input, false);
        TraceFiles.write(path, original.trace());
        RunTrace persisted = TraceFiles.read(path);
        require(original.trace().equals(persisted), "trace roundtrip changed");
        require(original.equals(ReductionWorkload.replay(input, false, persisted)), "fresh replay changed");
        require(original.outcome().equals(FailureReducer.Outcome.fail(ReductionWorkload.SIGNATURE)), "target failure lost");
    }

    /** A fresh exhaustive singleton/closure deletion check independent of reducer bookkeeping. */
    private static void verifyOneMinimal(List<FailureReducer.Event> input, FailureReducer.Mode mode) {
        for (FailureReducer.Event removed : input) {
            var excluded = new java.util.HashSet<String>();
            excluded.add(removed.id());
            List<FailureReducer.Event> candidate = new ArrayList<>();
            var retained = new java.util.HashSet<String>();
            boolean valid = true;
            for (FailureReducer.Event event : input) {
                if (mode == FailureReducer.Mode.DEPENDENCY_CLOSED
                        && event.requires().stream().anyMatch(excluded::contains)) excluded.add(event.id());
                if (!excluded.contains(event.id())) {
                    if (!retained.containsAll(event.requires())) valid = false;
                    retained.add(event.id());
                    candidate.add(event);
                }
            }
            if (valid) require(!ReductionWorkload.record(candidate, false).outcome()
                    .equals(FailureReducer.Outcome.fail(ReductionWorkload.SIGNATURE)), "not one-minimal");
        }
    }

    private static void persistInput(Path path, List<FailureReducer.Event> input) throws IOException {
        List<String> lines = new ArrayList<>(List.of("id\trequires"));
        for (FailureReducer.Event event : input) lines.add(event.id() + "\t" + String.join(",", event.requires()));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeEnvironment(Path root, Path output) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String folder : List.of("async-replay-core/src/main/java", "async-replay-reducer/src/main/java")) {
            try (var paths = Files.walk(root.resolve(folder))) {
                paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        for (String path : List.of("pom.xml", "async-replay-core/pom.xml", "async-replay-reducer/pom.xml", "scripts/run_reducer.sh")) {
            sources.add(root.resolve(path));
        }
        sources.sort(java.util.Comparator.comparing(p -> root.relativize(p).toString()));
        MessageDigest aggregate = sha256();
        List<String> entries = new ArrayList<>();
        for (Path path : sources) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            byte[] hash = sha256().digest(Files.readAllBytes(path));
            aggregate.update(relative.getBytes(StandardCharsets.UTF_8)); aggregate.update((byte) 0);
            aggregate.update(hash); aggregate.update((byte) 0);
            entries.add("{\"path\":" + json(relative) + ",\"sha256\":" + json(HexFormat.of().formatHex(hash)) + "}");
        }
        String content = "{\"date\":\"2026-10-16\",\"java\":" + json(System.getProperty("java.runtime.version"))
                + ",\"vendor\":" + json(System.getProperty("java.vendor")) + ",\"os\":" + json(System.getProperty("os.name"))
                + ",\"arch\":" + json(System.getProperty("os.arch")) + ",\"compile_release\":17,\"budget\":" + BUDGET
                + ",\"source_hash_scope\":\"main Java in core/reducer, their POMs, parent POM, runner script\""
                + ",\"source_sha256\":" + json(HexFormat.of().formatHex(aggregate.digest()))
                + ",\"source_hash_format\":\"sorted relative UTF-8 path, NUL, binary SHA-256, NUL\""
                + ",\"source_files\":[" + String.join(",", entries) + "]}\n";
        Files.writeString(output.resolve("environment.json"), content, StandardCharsets.UTF_8);
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Usage: sh scripts/run_reducer.sh <empty-output-dir>");
        try {
            run(Path.of(System.getProperty("asyncreplay.repoRoot", ".")), Path.of(args[0]));
            System.out.println("REDUCER EXPERIMENT PASS: see " + args[0]);
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}
