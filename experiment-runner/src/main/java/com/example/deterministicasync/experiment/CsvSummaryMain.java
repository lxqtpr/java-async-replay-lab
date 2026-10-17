package com.example.deterministicasync.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CsvSummaryMain {
    private static final List<String> SUITE_ORDER = List.of("naive", "awaitility", "extension");

    private CsvSummaryMain() {
    }

    public static void main(String[] args) throws IOException {
        boolean csvOutput = args.length > 0 && "--csv".equals(args[0]);
        int pathArgIndex = csvOutput ? 1 : 0;
        Path csvPath = args.length > pathArgIndex
                ? Path.of(args[pathArgIndex])
                : Path.of("artifacts/results.csv");

        if (!Files.exists(csvPath)) {
            System.out.println("CSV not found: " + csvPath.toAbsolutePath());
            System.out.println("Run scripts/run_experiment.sh first.");
            return;
        }

        List<String> lines = Files.readAllLines(csvPath);
        if (lines.size() <= 1) {
            System.out.println("CSV has no data rows: " + csvPath.toAbsolutePath());
            return;
        }

        String[] header = lines.get(0).split(",", -1);
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            indices.put(header[i].trim(), i);
        }

        validateRequiredColumns(indices, Set.of("suite", "status", "failure_kind", "duration_ms"));

        Map<String, Stats> statsBySuite = new HashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }

            String[] parts = lines.get(i).split(",", -1);
            if (parts.length < header.length) {
                continue;
            }

            String suite = normalized(part(parts, indices, "suite"));
            if (suite.isBlank()) {
                continue;
            }

            String status = normalizedStatus(part(parts, indices, "status"));
            String failureKind = normalizedFailureKind(status, part(parts, indices, "failure_kind"));
            long durationMs = parseLong(part(parts, indices, "duration_ms"));
            String outcome = outcome(status, failureKind);

            Stats stats = statsBySuite.computeIfAbsent(suite, ignored -> new Stats());
            stats.total++;
            stats.statusCounts.merge(status, 1L, Long::sum);
            stats.outcomeCounts.merge(outcome, 1L, Long::sum);
            stats.durations.add(durationMs);
            stats.totalDurationMs += durationMs;
            stats.minDurationMs = Math.min(stats.minDurationMs, durationMs);
            stats.maxDurationMs = Math.max(stats.maxDurationMs, durationMs);

            if (!"success".equalsIgnoreCase(status)) {
                stats.failures++;
                stats.currentFailureStreak++;
                stats.longestFailureStreak = Math.max(stats.longestFailureStreak, stats.currentFailureStreak);
            } else {
                stats.currentFailureStreak = 0;
            }

            if ("timeout".equalsIgnoreCase(failureKind)) {
                stats.timeoutFailures++;
            } else if ("assertion".equalsIgnoreCase(failureKind)) {
                stats.assertionFailures++;
            } else if ("aborted".equalsIgnoreCase(failureKind)) {
                stats.abortedFailures++;
            } else if ("no_tests".equalsIgnoreCase(failureKind)) {
                stats.noTestsRuns++;
            } else if (!"success".equalsIgnoreCase(failureKind)) {
                stats.otherFailures++;
            }
        }

        if (csvOutput) {
            printCsv(statsBySuite);
        } else {
            printPretty(csvPath, statsBySuite);
        }
    }

    private static void printCsv(Map<String, Stats> statsBySuite) {
        System.out.println(
                "Suite,runs,failures,failureRate,instabilityRate,reproducibleStatus,"
                        + "outcomeInstabilityRate,reproducibleOutcome,"
                        + "timeoutFailures,assertionFailures,abortedFailures,noTestsRuns,otherFailures,"
                        + "avgDurationMs,medianDurationMs,minDurationMs,maxDurationMs,durationRangeMs,longestFailureStreak"
        );
        sortedSuites(statsBySuite).forEach(entry -> printCsvSuite(entry.getKey(), entry.getValue()));
    }

    private static void printCsvSuite(String suite, Stats stats) {
        double failureRate = stats.total == 0 ? 0 : (double) stats.failures / stats.total;
        long dominantStatusCount = dominantCount(stats.statusCounts);
        long dominantOutcomeCount = dominantCount(stats.outcomeCounts);
        double instabilityRate = stats.total == 0 ? 0 : (double) (stats.total - dominantStatusCount) / stats.total;
        double outcomeInstabilityRate = stats.total == 0 ? 0 : (double) (stats.total - dominantOutcomeCount) / stats.total;
        String reproducibleStatus = stats.statusCounts.size() <= 1 ? "yes" : "no";
        String reproducibleOutcome = stats.outcomeCounts.size() <= 1 ? "yes" : "no";
        long avgDuration = stats.total == 0 ? 0 : stats.totalDurationMs / stats.total;
        long medianDuration = median(stats.durations);
        long minDuration = stats.durations.isEmpty() ? 0 : stats.minDurationMs;
        long maxDuration = stats.durations.isEmpty() ? 0 : stats.maxDurationMs;
        long range = stats.durations.isEmpty() ? 0 : maxDuration - minDuration;

        System.out.printf(
                Locale.ROOT,
                "%s,%d,%d,%.4f,%.4f,%s,%.4f,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                suite,
                stats.total,
                stats.failures,
                failureRate,
                instabilityRate,
                reproducibleStatus,
                outcomeInstabilityRate,
                reproducibleOutcome,
                stats.timeoutFailures,
                stats.assertionFailures,
                stats.abortedFailures,
                stats.noTestsRuns,
                stats.otherFailures,
                avgDuration,
                medianDuration,
                minDuration,
                maxDuration,
                range,
                stats.longestFailureStreak
        );
    }

    private static void printPretty(Path csvPath, Map<String, Stats> statsBySuite) {
        System.out.println("Experiment summary");
        System.out.println("CSV: " + csvPath.toAbsolutePath());
        System.out.println();
        System.out.printf(
                Locale.ROOT,
                "%-11s %6s %10s %13s %14s %14s %-28s %9s %9s %13s %12s%n",
                "suite",
                "runs",
                "failures",
                "failureRate",
                "statusStable",
                "outcomeStable",
                "failureKinds",
                "avgMs",
                "medianMs",
                "min..maxMs",
                "maxFailSeq"
        );
        System.out.println(
                "----------- ------ ---------- ------------- -------------- -------------- "
                        + "---------------------------- --------- --------- ------------- ------------"
        );

        sortedSuites(statsBySuite).forEach(entry -> printPrettySuite(entry.getKey(), entry.getValue()));
    }

    private static void printPrettySuite(String suite, Stats stats) {
        double failureRate = stats.total == 0 ? 0 : (double) stats.failures / stats.total;
        String statusStable = stats.statusCounts.size() <= 1 ? "yes" : "no";
        String outcomeStable = stats.outcomeCounts.size() <= 1 ? "yes" : "no";
        long avgDuration = stats.total == 0 ? 0 : stats.totalDurationMs / stats.total;
        long medianDuration = median(stats.durations);
        long minDuration = stats.durations.isEmpty() ? 0 : stats.minDurationMs;
        long maxDuration = stats.durations.isEmpty() ? 0 : stats.maxDurationMs;

        System.out.printf(
                Locale.ROOT,
                "%-11s %6d %10s %13s %14s %14s %-28s %9d %9d %13s %12d%n",
                suite,
                stats.total,
                stats.failures + "/" + stats.total,
                formatPercent(failureRate),
                statusStable,
                outcomeStable,
                failureBreakdown(stats),
                avgDuration,
                medianDuration,
                minDuration + ".." + maxDuration,
                stats.longestFailureStreak
        );
    }

    private static List<Map.Entry<String, Stats>> sortedSuites(Map<String, Stats> statsBySuite) {
        return statsBySuite.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, Stats> entry) -> suiteOrder(entry.getKey()))
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
    }

    private static int suiteOrder(String suite) {
        int index = SUITE_ORDER.indexOf(suite);
        return index >= 0 ? index : SUITE_ORDER.size();
    }

    private static String failureBreakdown(Stats stats) {
        List<String> parts = new ArrayList<>();
        addBreakdown(parts, "timeout", stats.timeoutFailures);
        addBreakdown(parts, "assertion", stats.assertionFailures);
        addBreakdown(parts, "aborted", stats.abortedFailures);
        addBreakdown(parts, "no_tests", stats.noTestsRuns);
        addBreakdown(parts, "other", stats.otherFailures);
        return parts.isEmpty() ? "-" : String.join(" ", parts);
    }

    private static void addBreakdown(List<String> parts, String label, long value) {
        if (value > 0) {
            parts.add(label + "=" + value);
        }
    }

    private static String formatPercent(double rate) {
        return String.format(Locale.ROOT, "%.2f%%", rate * 100);
    }

    private static long dominantCount(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private static void validateRequiredColumns(Map<String, Integer> indices, Set<String> requiredColumns) {
        Set<String> missing = new HashSet<>();
        for (String requiredColumn : requiredColumns) {
            if (!indices.containsKey(requiredColumn)) {
                missing.add(requiredColumn);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("CSV is missing required columns: " + String.join(", ", missing));
        }
    }

    private static String outcome(String status, String failureKind) {
        if (failureKind == null || failureKind.isBlank() || "success".equalsIgnoreCase(failureKind)) {
            return status;
        }
        return status + "/" + failureKind;
    }

    private static String part(String[] parts, Map<String, Integer> indices, String key) {
        Integer idx = indices.get(key);
        if (idx == null || idx >= parts.length) {
            return "";
        }
        return parts[idx];
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizedStatus(String value) {
        String normalized = normalized(value).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String normalizedFailureKind(String status, String value) {
        String normalized = normalized(value).toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "success".equals(status) ? "success" : "unknown";
    }

    private static long parseLong(String value) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            return 0L;
        }
        return Long.parseLong(normalized);
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }

        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static final class Stats {
        long total;
        long failures;
        long timeoutFailures;
        long assertionFailures;
        long abortedFailures;
        long noTestsRuns;
        long otherFailures;
        long totalDurationMs;
        long minDurationMs = Long.MAX_VALUE;
        long maxDurationMs = Long.MIN_VALUE;
        long currentFailureStreak;
        long longestFailureStreak;
        final List<Long> durations = new ArrayList<>();
        final Map<String, Long> statusCounts = new HashMap<>();
        final Map<String, Long> outcomeCounts = new HashMap<>();
    }
}
