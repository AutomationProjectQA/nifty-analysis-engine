package com.nifty.analysis.backtest;

import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.config.TradingPolicy;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.OnnxModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-4 replay harness (audit #215 / T18-4): replays historical snapshots through the decision
 * logic for a GIVEN policy and reports performance — WITHOUT persisting anything (unlike
 * {@link BacktestingEngine}, which writes simulated trades to the live tables). This makes it safe to
 * A/B a candidate policy (e.g. a different gating threshold or reward:risk) against the live policy
 * over the same window before changing config in production.
 *
 * <p>Same fidelity caveat as the backtest: historical per-strike premiums aren't stored, so entry is
 * a modeled baseline and intraday option movement is approximated (0.5 delta + theta).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplayHarnessService {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final ConfidenceEngine confidenceEngine;
    private final CriticAgent criticAgent;
    private final OnnxModelService onnxModelService;
    private final TechnicalAgent technicalAgent;
    private final TradingPolicy tradingPolicy;

    @Value("${nifty.model-weight:0.4}")
    private double modelWeight;
    @Value("${nifty.order-execution.lot-size:65}")
    private int lotSize;
    @Value("${nifty.backtest.entry-premium:150.0}")
    private double entryPremium;
    @Value("${nifty.backtest.brokerage-per-trade:40.0}")
    private double brokeragePerTrade;
    @Value("${nifty.backtest.slippage-percent:0.5}")
    private double slippagePercent;
    @Value("${nifty.backtest.theta-decay-per-hour:0.5}")
    private double thetaDecayPerHour;

    /** A policy variant to replay. */
    public record Policy(double gatingThreshold, double targetProfitPercent, double stopLossPercent) {}

    /** Result of one replay run (no DB writes). */
    public record ReplayRun(String label, double gatingThreshold, double targetProfitPercent,
                            double stopLossPercent, int totalSignals, double netPnlInr,
                            BacktestingEngine.BacktestMetrics metrics) {}

    /** A/B comparison of the live policy vs a candidate over the same window. */
    public record ComparisonReport(LocalDateTime start, LocalDateTime end, int snapshotsEvaluated,
                                   List<ReplayRun> runs) {}

    /** Replays the live (current) policy vs a candidate gating threshold over the window. */
    @Transactional(readOnly = true)
    public ComparisonReport compareGating(LocalDateTime start, LocalDateTime end, double candidateGating) {
        Loaded loaded = load(start, end);
        Policy live = new Policy(tradingPolicy.getGatingThreshold(),
                tradingPolicy.getTargetProfitPercent(), tradingPolicy.getStopLossPercent());
        Policy candidate = new Policy(candidateGating,
                tradingPolicy.getTargetProfitPercent(), tradingPolicy.getStopLossPercent());

        ReplayRun current = simulate("current (gate=" + live.gatingThreshold() + ")", live, loaded);
        ReplayRun cand = simulate("candidate (gate=" + candidateGating + ")", candidate, loaded);
        return new ComparisonReport(start, end, loaded.evalSnapshots.size(), List.of(current, cand));
    }

    /** Replays a single explicit policy over the window. */
    @Transactional(readOnly = true)
    public ReplayRun replay(LocalDateTime start, LocalDateTime end, String label, Policy policy) {
        return simulate(label, policy, load(start, end));
    }

    // --- internals ---

    private record Loaded(List<MarketSnapshot> all, List<MarketSnapshot> evalSnapshots) {}

    private Loaded load(LocalDateTime start, LocalDateTime end) {
        LocalDateTime historyStart = start.minusDays(10); // warm-up for features
        List<MarketSnapshot> all = marketSnapshotRepository.findAll().stream()
                .filter(s -> s.getSnapshotTime() != null
                        && !s.getSnapshotTime().isBefore(historyStart) && !s.getSnapshotTime().isAfter(end))
                .sorted((a, b) -> a.getSnapshotTime().compareTo(b.getSnapshotTime()))
                .toList();
        List<MarketSnapshot> eval = all.stream().filter(s -> !s.getSnapshotTime().isBefore(start)).toList();
        return new Loaded(all, eval);
    }

    private ReplayRun simulate(String label, Policy policy, Loaded loaded) {
        List<MarketSnapshot> snapshots = loaded.evalSnapshots;
        List<Double> netPnls = new ArrayList<>();
        int totalSignals = 0;
        List<SimTrade> active = new ArrayList<>();

        for (int i = 1; i < snapshots.size(); i++) {
            MarketSnapshot current = snapshots.get(i);
            double spot = current.getNiftySpot();
            double spotChange = spot - snapshots.get(i - 1).getNiftySpot();

            // Resolve open trades (mark-to-market against SL/target2).
            List<SimTrade> done = new ArrayList<>();
            for (SimTrade t : active) {
                double price = markToMarket(t, spot, current.getSnapshotTime());
                if (price <= t.stopLoss) { netPnls.add(close(t, t.stopLoss, current.getSnapshotTime())); done.add(t); }
                else if (price >= t.target2) { netPnls.add(close(t, t.target2, current.getSnapshotTime())); done.add(t); }
            }
            active.removeAll(done);

            // Look for a fresh signal under THIS policy (same simplified path as the backtest).
            List<OptionSnapshot> chain = optionSnapshotRepository.findBySnapshotTime(current.getSnapshotTime());
            if (chain.isEmpty()) continue;
            List<OptionSnapshotDto> dtos = chain.stream().map(o -> new OptionSnapshotDto(
                    o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                    o.getIv(), o.getPcr(), o.getMaxPain(), o.getCeVolume(), o.getPeVolume(), o.getSnapshotTime(),
                    o.getCeLtp(), o.getPeLtp())).toList();

            AgentResponse tech = technicalAgent.analyze(current);
            boolean bull = "BULLISH".equals(tech.bias());
            boolean bear = "BEARISH".equals(tech.bias());
            if (!bull && !bear) continue;

            TechnicalAgent.TechnicalFeatures f = technicalAgent.getFeatures(current, loaded.all);
            double modelBull = onnxModelService.predictBullishProbability(f.rsi(), f.spotToEma20(), f.ema20ToEma50(),
                    f.vix(), f.prevDailyReturn(), f.bbWidth(), f.macdHist(), f.volumeRatio());
            double modelConf = bull ? modelBull : 100.0 - modelBull;
            double agentConf = confidenceEngine.calculateRawConfidence(current, dtos, spotChange, bull).rawConfidence();
            double blended = modelWeight * modelConf + (1.0 - modelWeight) * agentConf;
            double finalConf = criticAgent.evaluateAndApplyPenalties(blended, current, chain, bull).adjustedConfidence();

            if (finalConf < policy.gatingThreshold()) continue;

            double target1 = entryPremium * (1.0 + policy.targetProfitPercent() / 200.0);
            double target2 = entryPremium * (1.0 + policy.targetProfitPercent() / 100.0);
            double stopLoss = entryPremium * (1.0 - policy.stopLossPercent() / 100.0);
            active.add(new SimTrade(bull, spot, current.getSnapshotTime(), entryPremium, stopLoss, target1, target2));
            totalSignals++;
        }

        // Close still-open trades at the last mark.
        if (!snapshots.isEmpty()) {
            MarketSnapshot lastSnap = snapshots.get(snapshots.size() - 1);
            for (SimTrade t : active) {
                double price = markToMarket(t, lastSnap.getNiftySpot(), lastSnap.getSnapshotTime());
                netPnls.add(close(t, price, lastSnap.getSnapshotTime()));
            }
        }

        BacktestingEngine.BacktestMetrics m = BacktestingEngine.computeMetrics(netPnls);
        double net = netPnls.stream().mapToDouble(Double::doubleValue).sum();
        return new ReplayRun(label, policy.gatingThreshold(), policy.targetProfitPercent(),
                policy.stopLossPercent(), totalSignals, round2(net), m);
    }

    private double markToMarket(SimTrade t, double spot, LocalDateTime nowTime) {
        double intrinsic = t.isCall ? (spot - t.entrySpot) * 0.5 : (t.entrySpot - spot) * 0.5;
        double hours = Duration.between(t.entryTime, nowTime).toSeconds() / 3600.0;
        return t.entryPremium + intrinsic - thetaDecayPerHour * Math.max(0.0, hours);
    }

    /** Net P&L of one closed trade incl. slippage + brokerage (no persistence). */
    private double close(SimTrade t, double exitLevel, LocalDateTime closeTime) {
        double entryFill = t.entryPremium * (1.0 + slippagePercent / 100.0);
        double exitFill = exitLevel * (1.0 - slippagePercent / 100.0);
        double gross = (exitFill - entryFill) * lotSize;
        return round2(gross - brokeragePerTrade);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static final class SimTrade {
        final boolean isCall;
        final double entrySpot;
        final LocalDateTime entryTime;
        final double entryPremium, stopLoss, target1, target2;
        SimTrade(boolean isCall, double entrySpot, LocalDateTime entryTime,
                 double entryPremium, double stopLoss, double target1, double target2) {
            this.isCall = isCall; this.entrySpot = entrySpot; this.entryTime = entryTime;
            this.entryPremium = entryPremium; this.stopLoss = stopLoss; this.target1 = target1; this.target2 = target2;
        }
    }
}
