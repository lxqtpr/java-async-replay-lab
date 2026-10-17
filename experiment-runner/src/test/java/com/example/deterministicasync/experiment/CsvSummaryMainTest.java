package com.example.deterministicasync.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class CsvSummaryMainTest {

    @Test
    void printsReadableConsoleTableByDefault() throws Exception {
        Path csv = Files.createTempFile("csv-summary", ".csv");
        Files.writeString(
                csv,
                """
                suite,status,failure_kind,duration_ms
                demo,success,success,100
                demo,fail,assertion,250
                demo,fail,timeout,150
                """,
                StandardCharsets.UTF_8
        );

        Locale originalLocale = Locale.getDefault();
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"));
            System.setOut(capture);

            CsvSummaryMain.main(new String[]{csv.toString()});
        } finally {
            System.setOut(originalOut);
            Locale.setDefault(originalLocale);
            Files.deleteIfExists(csv);
        }

        String[] lines = buffer.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals("Experiment summary", lines[0]);
        assertEquals(
                "demo             3        2/3        66.67%             no             no timeout=1 assertion=1              166       150      100..250            2",
                lines[5]
        );
    }

    @Test
    void printsOutcomeMetricsWithStableCsvFormattingWhenRequested() throws Exception {
        Path csv = Files.createTempFile("csv-summary", ".csv");
        Files.writeString(
                csv,
                """
                suite,status,failure_kind,duration_ms
                demo,success,success,100
                demo,fail,assertion,250
                demo,fail,timeout,150
                """,
                StandardCharsets.UTF_8
        );

        Locale originalLocale = Locale.getDefault();
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"));
            System.setOut(capture);

            CsvSummaryMain.main(new String[]{"--csv", csv.toString()});
        } finally {
            System.setOut(originalOut);
            Locale.setDefault(originalLocale);
            Files.deleteIfExists(csv);
        }

        String[] lines = buffer.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(
                "Suite,runs,failures,failureRate,instabilityRate,reproducibleStatus,"
                        + "outcomeInstabilityRate,reproducibleOutcome,"
                        + "timeoutFailures,assertionFailures,abortedFailures,noTestsRuns,otherFailures,"
                        + "avgDurationMs,medianDurationMs,minDurationMs,maxDurationMs,durationRangeMs,longestFailureStreak",
                lines[0]
        );
        assertEquals(
                "demo,3,2,0.6667,0.3333,no,0.6667,no,1,1,0,0,0,166,150,100,250,150,2",
                lines[1]
        );
    }
}
