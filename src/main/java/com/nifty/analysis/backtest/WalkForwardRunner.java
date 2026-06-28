package com.nifty.analysis.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the backtest over consecutive out-of-sample windows ("folds") and reports per-fold
 * metrics plus an aggregate, so an edge must hold across multiple periods — not just overfit
 * one stretch of history. (Per-fold model/threshold calibration plugs in at Phase 4; today
 * each fold uses the same fixed config, so this measures cross-period stability of the edge.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalkForwardRunner {

    private final BacktestingEngine backtestingEngine;

    public Map<String, Object> run(LocalDateTime start, LocalDateTime end, int folds) {
        List<LocalDateTime[]> windows = splitWindows(start, end, folds);
        if (windows.isEmpty()) {
            return Map.of("status", "FAILED", "error", "Invalid date range / fold count");
        }

        List<Map<String, Object>> foldResults = new ArrayList<>();
        double sumExpectancy = 0.0, sumNet = 0.0, sumWinRate = 0.0;
        int profitableFolds = 0, evaluatedFolds = 0;

        for (int i = 0; i < windows.size(); i++) {
            LocalDateTime[] w = windows.get(i);
            Map<String, Object> r = backtestingEngine.runBacktest(w[0], w[1]);
            r.put("fold", i + 1);
            r.put("from", w[0].toString());
            r.put("to", w[1].toString());
            foldResults.add(r);

            if ("SUCCESS".equals(r.get("status"))) {
                evaluatedFolds++;
                double expectancy = toDouble(r.get("expectancyInr"));
                double net = toDouble(r.get("netPnlInr"));
                sumExpectancy += expectancy;
                sumNet += net;
                sumWinRate += toDouble(r.get("netWinRatePercentage"));
                if (net > 0) profitableFolds++;
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("status", "SUCCESS");
        out.put("foldCount", windows.size());
        out.put("evaluatedFolds", evaluatedFolds);
        out.put("profitableFolds", profitableFolds);
        out.put("avgExpectancyInr", evaluatedFolds > 0 ? round2(sumExpectancy / evaluatedFolds) : 0.0);
        out.put("avgNetWinRatePercentage", evaluatedFolds > 0 ? round2(sumWinRate / evaluatedFolds) : 0.0);
        out.put("totalNetPnlInr", round2(sumNet));
        out.put("folds", foldResults);
        log.info("Walk-forward complete: {}/{} folds profitable, avg expectancy {} INR/trade",
                profitableFolds, evaluatedFolds, out.get("avgExpectancyInr"));
        return out;
    }

    /** Splits [start, end] into {@code folds} equal consecutive windows. Pure + unit-tested. */
    static List<LocalDateTime[]> splitWindows(LocalDateTime start, LocalDateTime end, int folds) {
        List<LocalDateTime[]> windows = new ArrayList<>();
        if (start == null || end == null || folds < 1 || !end.isAfter(start)) {
            return windows;
        }
        long totalSeconds = Duration.between(start, end).toSeconds();
        long step = totalSeconds / folds;
        if (step <= 0) {
            windows.add(new LocalDateTime[]{start, end});
            return windows;
        }
        for (int i = 0; i < folds; i++) {
            LocalDateTime ws = start.plusSeconds(step * i);
            LocalDateTime we = (i == folds - 1) ? end : start.plusSeconds(step * (i + 1));
            windows.add(new LocalDateTime[]{ws, we});
        }
        return windows;
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
