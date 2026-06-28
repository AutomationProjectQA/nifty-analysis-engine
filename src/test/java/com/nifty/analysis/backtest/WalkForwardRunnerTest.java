package com.nifty.analysis.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkForwardRunnerTest {

    @Test
    void splitWindows_dividesRangeIntoConsecutiveFolds() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 4, 0, 0); // 3 days
        List<LocalDateTime[]> w = WalkForwardRunner.splitWindows(start, end, 3);

        assertEquals(3, w.size());
        assertEquals(start, w.get(0)[0]);
        assertEquals(end, w.get(2)[1]);                       // last fold ends exactly at end
        assertEquals(w.get(0)[1], w.get(1)[0]);               // folds are contiguous
        assertEquals(w.get(1)[1], w.get(2)[0]);
        assertEquals(LocalDateTime.of(2026, 6, 2, 0, 0), w.get(0)[1]); // 1-day folds
    }

    @Test
    void splitWindows_invalidRange_returnsEmpty() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 1, 0, 0);
        assertTrue(WalkForwardRunner.splitWindows(t, t, 3).isEmpty());      // zero range
        assertTrue(WalkForwardRunner.splitWindows(t.plusDays(1), t, 3).isEmpty()); // end before start
        assertTrue(WalkForwardRunner.splitWindows(t, t.plusDays(1), 0).isEmpty()); // no folds
    }
}
