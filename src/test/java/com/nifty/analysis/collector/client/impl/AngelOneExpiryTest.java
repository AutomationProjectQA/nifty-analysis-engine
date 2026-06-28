package com.nifty.analysis.collector.client.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AngelOneExpiryTest {

    /** Parses Angel-style uppercase "ddMMMyyyy" expiry strings (case-insensitive, as the client does). */
    private static LocalDate parse(String s) {
        try {
            DateTimeFormatter f = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive().appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH);
            return LocalDate.parse(s.trim(), f);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void nearestExpiryFrom_picksSmallestOnOrAfterDate() {
        List<String> expiries = List.of("16JUN2026", "23JUN2026", "30JUN2026", "28JUL2026");
        Optional<LocalDate> r = AngelOneDataClient.nearestExpiryFrom(
                expiries, LocalDate.of(2026, 6, 18), AngelOneExpiryTest::parse);
        assertEquals(LocalDate.of(2026, 6, 23), r.orElseThrow()); // skips the already-passed 16-Jun
    }

    @Test
    void nearestExpiryFrom_ignoresUnparseableAndEmpty() {
        List<String> expiries = List.of("", "NOTADATE", "30JUN2026");
        Optional<LocalDate> r = AngelOneDataClient.nearestExpiryFrom(
                expiries, LocalDate.of(2026, 6, 1), AngelOneExpiryTest::parse);
        assertEquals(LocalDate.of(2026, 6, 30), r.orElseThrow());
    }

    @Test
    void nearestExpiryFrom_emptyWhenAllPassed() {
        List<String> expiries = List.of("16JUN2026");
        assertTrue(AngelOneDataClient.nearestExpiryFrom(
                expiries, LocalDate.of(2026, 6, 20), AngelOneExpiryTest::parse).isEmpty());
    }
}
