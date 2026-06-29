package com.nifty.analysis.service;

import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase-4 adaptive-AI observability (audit #216 / T18-6): measures which confidence FACTORS actually
 * separate winners from losers. For each factor (Trend, OI, PCR, VWAP, RSI, Futures, …) it compares
 * the average score on winning trades vs losing trades — a large positive "edge" means the factor is
 * predictive; an edge near zero means it adds noise and is a candidate to down-weight or prune.
 *
 * <p>Read-only; never affects the decision path. It tells you what to tune, it doesn't tune anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FactorEffectivenessService {

    private final TradeResultRepository tradeResultRepository;
    private final SignalExplanationRepository signalExplanationRepository;

    /** Per-factor separation between winners and losers. */
    public record FactorEdge(String factor, int winSamples, int lossSamples,
                             double avgScoreOnWins, double avgScoreOnLosses, double edge) {}

    public record Report(int resolvedTrades, int wins, int losses, List<FactorEdge> factors) {}

    public Report report() {
        List<TradeResult> results = tradeResultRepository.findAll();

        // Map each resolved signal id → won? (profit > 0)
        Map<Long, Boolean> wonBySignal = new HashMap<>();
        int wins = 0, losses = 0;
        for (TradeResult r : results) {
            if (r.getSignal() == null || r.getProfitLoss() == null) continue;
            boolean won = r.getProfitLoss() > 0;
            wonBySignal.put(r.getSignal().getId(), won);
            if (won) wins++; else losses++;
        }
        if (wonBySignal.isEmpty()) {
            return new Report(0, 0, 0, List.of());
        }

        List<Long> signalIds = new ArrayList<>(wonBySignal.keySet());
        List<SignalExplanation> explanations = signalExplanationRepository.findBySignalIdIn(signalIds);

        // Accumulate per-factor sum/count split by win/loss.
        Map<String, double[]> agg = new HashMap<>(); // factor → [winSum, winCount, lossSum, lossCount]
        for (SignalExplanation e : explanations) {
            if (e.getFactor() == null || e.getScore() == null || e.getSignal() == null) continue;
            Boolean won = wonBySignal.get(e.getSignal().getId());
            if (won == null) continue;
            double[] a = agg.computeIfAbsent(e.getFactor(), k -> new double[4]);
            if (won) { a[0] += e.getScore(); a[1] += 1; }
            else     { a[2] += e.getScore(); a[3] += 1; }
        }

        List<FactorEdge> edges = new ArrayList<>(agg.size());
        for (Map.Entry<String, double[]> en : agg.entrySet()) {
            double[] a = en.getValue();
            int winN = (int) a[1];
            int lossN = (int) a[3];
            double avgWin = winN > 0 ? a[0] / winN : 0.0;
            double avgLoss = lossN > 0 ? a[2] / lossN : 0.0;
            edges.add(new FactorEdge(en.getKey(), winN, lossN,
                    round2(avgWin), round2(avgLoss), round2(avgWin - avgLoss)));
        }
        // Most predictive first (largest absolute separation between winners and losers).
        edges.sort((x, y) -> Double.compare(Math.abs(y.edge()), Math.abs(x.edge())));

        return new Report(wins + losses, wins, losses, edges);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
