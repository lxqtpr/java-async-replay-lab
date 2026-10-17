package dev.asyncreplay;

import dev.asyncreplay.corpus.CorpusExperiment;
import dev.asyncreplay.corpus.ScenarioCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CorpusExperimentTest {
    @TempDir Path temporary;

    @Test void persistedCsvRowsMatchTraceBytesStateAndFailureSignatureOnFreshReplay() throws Exception {
        Path output = temporary.resolve("run, \"four\"");
        CorpusExperiment.run(repositoryRoot(), output, 4);
        List<List<String>> records = csv(Files.readString(output.resolve("raw.csv")));
        assertEquals(List.of("scenario", "variant", "seed", "outcome", "failure_signature", "state",
                "steps", "trace_bytes", "replay_match", "wall_nanos"), records.get(0));
        assertEquals(33, records.size());
        int observedBugs = 0;
        for (List<String> row : records.subList(1, records.size())) {
            assertEquals(10, row.size());
            String scenario = row.get(0);
            boolean fixed = row.get(1).equals("fixed");
            Path tracePath = output.resolve("traces").resolve(scenario).resolve(row.get(1))
                    .resolve("seed-" + row.get(2) + ".trace");
            RunTrace trace = TraceFiles.read(tracePath);
            assertEquals(Long.parseLong(row.get(2)), trace.seed());
            assertEquals(Integer.parseInt(row.get(6)), trace.steps().size());
            assertEquals(Long.parseLong(row.get(7)), Files.size(tracePath));
            assertEquals("true", row.get(8));
            assertTrue(Long.parseLong(row.get(9)) >= 0);
            try (ControlledRuntime replay = ControlledRuntime.replay(trace)) {
                ScenarioCorpus.Observation observation = ScenarioCorpus.install(scenario, replay, fixed);
                String signature = "";
                try {
                    replay.drain(100);
                } catch (ScenarioCorpus.InvariantViolation failure) {
                    signature = failure.signature();
                }
                assertEquals(row.get(4), signature);
                assertEquals(row.get(5), observation.state());
                assertEquals(trace, replay.trace());
                assertEquals(0, replay.pendingTasks());
                replay.verifyReplayComplete();
            }
            if (fixed) assertEquals("PASS", row.get(3));
            if (row.get(3).equals("EXPECTED_BUG")) observedBugs++;
        }
        assertTrue(observedBugs >= 4);
        String summary = Files.readString(output.resolve("summary.json"));
        assertTrue(summary.contains("\"status\":\"PASS\""));
        assertTrue(summary.contains("\"rows\":32"));
        assertTrue(summary.contains("\"unexpected_errors\":0"));
    }

    @Test void invalidSeedsAndNonemptyOutputAreRejectedWithoutOverwritingData() throws Exception {
        Path empty = temporary.resolve("invalid");
        assertThrows(IllegalArgumentException.class, () -> CorpusExperiment.run(repositoryRoot(), empty, 0));
        assertThrows(IllegalArgumentException.class, () -> CorpusExperiment.run(repositoryRoot(), empty, -1));
        assertFalse(Files.exists(empty));
        Path occupied = Files.createDirectory(temporary.resolve("occupied"));
        Path sentinel = occupied.resolve("keep.txt");
        Files.writeString(sentinel, "existing experiment");
        assertThrows(IllegalArgumentException.class, () -> CorpusExperiment.run(repositoryRoot(), occupied, 4));
        assertEquals("existing experiment", Files.readString(sentinel));
        assertFalse(Files.exists(occupied.resolve("raw.csv")));
    }

    @Test void aSeedRangeWithoutEveryBugIsAnExplicitFailedAcceptanceGate() throws Exception {
        Path output = temporary.resolve("too-few-seeds");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CorpusExperiment.run(repositoryRoot(), output, 1));
        assertTrue(failure.getMessage().contains("acceptance failed"));
        String summary = Files.readString(output.resolve("summary.json"));
        assertTrue(summary.contains("\"status\":\"FAIL\""));
        assertTrue(summary.contains("\"passed\":false"));
        assertTrue(summary.contains("no buggy invariant failure"));
        assertEquals(9, csv(Files.readString(output.resolve("raw.csv"))).size());
    }

    @Test void sourceFingerprintExcludesGeneratedDataAndChangesWithSourceContent() throws Exception {
        // A synthetic source tree isolates the manifest contract from concurrent repository edits.
        Path sources = Files.createDirectory(temporary.resolve("manifest-source"));
        Path source = sources.resolve("Example.java");
        Files.writeString(source, "final class Example {}\n");
        Path first = sources.resolve("artifacts/first");
        CorpusExperiment.run(sources, first, 4);
        String hash = fingerprint(first);
        Files.createDirectories(sources.resolve("target"));
        Files.writeString(sources.resolve("target/Generated.java"), "generated content\n");
        Files.createDirectories(sources.resolve("research-results/jdk21"));
        Files.writeString(sources.resolve("research-results/jdk21/result.properties"), "dataset=true\n");
        Files.writeString(sources.resolve("README.md"), "report written after the run\n");
        Path second = sources.resolve("artifacts/second");
        CorpusExperiment.run(sources, second, 4);
        assertEquals(hash, fingerprint(second));
        Files.writeString(source, "final class Example { int changed; }\n");
        Path third = sources.resolve("artifacts/third");
        CorpusExperiment.run(sources, third, 4);
        assertNotEquals(hash, fingerprint(third));
    }

    private static String fingerprint(Path output) throws Exception {
        var match = Pattern.compile("\"source_sha256\":\"([0-9a-f]{64})\"")
                .matcher(Files.readString(output.resolve("environment.json")));
        assertTrue(match.find());
        return match.group(1);
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("scripts/run_corpus.sh"))) {
            directory = directory.getParent();
        }
        return java.util.Objects.requireNonNull(directory, "repository root");
    }

    /** Enough CSV decoding to check persisted quoted fields, including escaped quotes/newlines. */
    private static List<List<String>> csv(String input) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < input.length() && input.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (!quoted && character == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (!quoted && character == '\n') {
                row.add(cell.toString());
                records.add(List.copyOf(row));
                row.clear();
                cell.setLength(0);
            } else cell.append(character);
        }
        assertFalse(quoted, "unterminated CSV field");
        assertTrue(row.isEmpty() && cell.isEmpty(), "CSV should end with a record newline");
        return records;
    }
}
