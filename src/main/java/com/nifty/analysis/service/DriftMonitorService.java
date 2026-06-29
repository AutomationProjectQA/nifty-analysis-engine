package com.nifty.analysis.service;

import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase-4 adaptive-AI observability (audit #212 / T18-6): concept-drift signal. Compares the most
 * recent N resolved trades to the full history — win-rate drift and confidence-distribution shift —
 * and flags {@code degraded} when recent win-rate falls materially below the historical baseline.
 * That's the cue that the model/rules are going stale and need retraining/retuning.
 *
 * <p>Recency is approximated by {@code TradeResult.id} (auto-increment = resolution order), since the
 * entity has no resolution timestamp. Read-only; never affects trading.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriftMonitorService {

    private static final String CALIBRATION_FACTOR = "Final_Confidence";

    @Value("${nifty.monitor.drift-window:20}")
    private int driftWindow;
    @Value("${nifty.monitor.drift-degrade-threshold:0.15}")
    private double degradeThreshold;

    private final TradeResultRepository tradeResultRepository;
    private final SignalExplanationRepository signalExplanationRepository;

    public record Report(int totalResolved, int recentWindow,
                         double overallWinRate, double recentWinRate, double winRateDrift,
                         double overallAvgConfidence, double recentAvgConfidence, double confidenceDrift,
                         boolean degraded) {}

    public Report report() {
        List<TradeResult> all = tradeResultRepository.findAll().stream()
                .filter(r -> r.getSignal() != null && r.getProfitLoss() != null)
                .sorted(Comparator.comparing(TradeResult::getId)) // ascending = oldest → newest
                .toList();
        if (all.isEmpty()) {
            return new Report(0, 0, 0, 0, 0, 0, 0, 0, false);
        }

        int window = Math.min(driftWindow, all.size());
        List<TradeResult> recent = all.subList(all.size() - window, all.size());

        double overallWin = winRate(all);
        double recentWin = winRate(recent);

        Map<Long, Double> confBySignal = confidenceBySignal(all);
        double overallConf = avgConfidence(all, confBySignal);
        double recentConf = avgConfidence(recent, confBySignal);

        boolean degraded = all.size() >= driftWindow && (overallWin - recentWin) >= degradeThreshold;
        if (degraded) {
            log.warn("Drift detected: recent win-rate {} is {} below historical {} (>= {} threshold).",
                    round4(recentWin), round4(overallWin - recentWin), round4(overallWin), degradeThreshold);
        }

        return new Report(all.size(), window,
                round4(overallWin), round4(recentWin), round4(recentWin - overallWin),
                round2(overallConf), round2(recentConf), round2(recentConf - overallConf),
                degraded);
    }

    private static double winRate(List<TradeResult> rs) {
        if (rs.isEmpty()) return 0.0;
        long wins = rs.stream().filter(r -> r.getProfitLoss() > 0).count();
        return (double) wins / rs.size();
    }

    private Map<Long, Double> confidenceBySignal(List<TradeResult> rs) {
        List<Long> ids = rs.stream().map(r -> r.getSignal().getId()).toList();
        if (ids.isEmpty()) return Map.of();
        return signalExplanationRepository.findBySignalIdIn(ids).stream()
                .filter(e -> CALIBRATION_FACTOR.equals(e.getFactor()) && e.getScore() != null && e.getSignal() != null)
                .collect(Collectors.toMap(e -> e.getSignal().getId(), SignalExplanation::getScore, (x, y) -> x));
    }

    private static double avgConfidence(List<TradeResult> rs, Map<Long, Double> confBySignal) {
        double sum = 0.0;
        int n = 0;
        for (TradeResult r : rs) {
            Double c = confBySignal.get(r.getSignal().getId());
            if (c != null) { sum += c; n++; }
        }
        return n > 0 ? sum / n : 0.0;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
