package com.nifty.analysis.backtest;

import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.service.OnnxModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays historical snapshots through the SAME decision logic the live engine uses
 * (ONNX/agent confidence blend + config gating threshold + percent-based levels), and
 * models realistic execution costs (brokerage, slippage, theta decay) so the reported
 * P&L and win rate are trustworthy rather than optimistic.
 *
 * <p>Note: historical per-strike option premiums are not stored, so entry premium is a
 * configurable modeled baseline and intraday option movement is approximated with a
 * 0.5 delta plus time decay. This is an approximation, not a tick-accurate replay.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestingEngine {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;

    private final ConfidenceEngine confidenceEngine;
    private final CriticAgent criticAgent;

    private final TradeSignalRepository tradeSignalRepository;
    private final TradeResultRepository tradeResultRepository;
    private final OnnxModelService onnxModelService;
    private final TechnicalAgent technicalAgent;

    // --- Mirror the live decision/risk policy ---
    @Value("${nifty.gating-threshold:60.0}")
    private double gatingThreshold;
    @Value("${nifty.model-weight:0.4}")
    private double modelWeight;
    @Value("${nifty.risk.target-profit-percent:2.0}")
    private double targetProfitPercent;
    @Value("${nifty.risk.stop-loss-percent:40.0}")
    private double stopLossPercent;
    @Value("${nifty.order-execution.lot-size:65}")
    private int lotSize;

    // --- Execution realism ---
    @Value("${nifty.backtest.entry-premium:150.0}")
    private double entryPremium;
    @Value("${nifty.backtest.brokerage-per-trade:40.0}")
    private double brokeragePerTrade;
    @Value("${nifty.backtest.slippage-percent:0.5}")
    private double slippagePercent;
    @Value("${nifty.backtest.theta-decay-per-hour:0.5}")
    private double thetaDecayPerHour;

    @Transactional
    public Map<String, Object> runBacktest(LocalDateTime start, LocalDateTime end) {
        log.info("Starting historical backtesting simulation from {} to {}...", start, end);

        LocalDateTime historyStart = start.minusDays(10);
        List<MarketSnapshot> allSnapshots = marketSnapshotRepository.findAll().stream()
                .filter(s -> !s.getSnapshotTime().isBefore(historyStart) && !s.getSnapshotTime().isAfter(end))
                .sorted((s1, s2) -> s1.getSnapshotTime().compareTo(s2.getSnapshotTime()))
                .toList();

        List<MarketSnapshot> snapshots = allSnapshots.stream()
                .filter(s -> !s.getSnapshotTime().isBefore(start))
                .toList();

        if (snapshots.size() < 2) {
            return Map.of("status", "FAILED", "error", "Insufficient historical market snapshots in range");
        }

        int totalSignals = 0;
        int target1Touches = 0;
        int target2Hits = 0;
        int stopLossHits = 0;
        int expiredCount = 0;
        double grossPnl = 0.0;
        double totalCosts = 0.0;
        long totalHoldingSeconds = 0L;
        // Per-trade NET P&L in close order — feeds the risk metrics (expectancy, profit factor, drawdown).
        List<Double> tradeNetPnls = new ArrayList<>();

        List<ActiveSimulatedTrade> activeTrades = new ArrayList<>();

        for (int i = 1; i < snapshots.size(); i++) {
            MarketSnapshot current = snapshots.get(i);
            MarketSnapshot prev = snapshots.get(i - 1);

            double spot = current.getNiftySpot();
            double spotChange = spot - prev.getNiftySpot();

            // 1. Mark-to-market existing trades and resolve SL / target hits.
            List<ActiveSimulatedTrade> completed = new ArrayList<>();
            for (ActiveSimulatedTrade trade : activeTrades) {
                double price = markToMarket(trade, spot, current.getSnapshotTime());

                if (price <= trade.stopLoss) {
                    Outcome o = closeTrade(trade, "STOP_LOSS", trade.stopLoss, current.getSnapshotTime(), 0.0);
                    grossPnl += o.grossInr;
                    totalCosts += o.costInr;
                    totalHoldingSeconds += o.holdingSeconds;
                    tradeNetPnls.add(o.grossInr - o.costInr);
                    stopLossHits++;
                    completed.add(trade);
                } else if (price >= trade.target2) {
                    Outcome o = closeTrade(trade, "TARGET2", trade.target2, current.getSnapshotTime(), 100.0);
                    grossPnl += o.grossInr;
                    totalCosts += o.costInr;
                    totalHoldingSeconds += o.holdingSeconds;
                    tradeNetPnls.add(o.grossInr - o.costInr);
                    target2Hits++;
                    completed.add(trade);
                } else if (price >= trade.target1 && !trade.hitTarget1) {
                    trade.hitTarget1 = true;
                    target1Touches++;
                }
            }
            activeTrades.removeAll(completed);

            // 2. Look for a fresh signal using the SAME gating as the live engine.
            List<OptionSnapshot> optionChain = optionSnapshotRepository.findBySnapshotTime(current.getSnapshotTime());
            if (optionChain.isEmpty()) {
                continue;
            }
            List<OptionSnapshotDto> optionDtos = optionChain.stream().map(o -> new OptionSnapshotDto(
                    o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                    o.getIv(), o.getPcr(), o.getMaxPain(), o.getCeVolume(), o.getPeVolume(), o.getSnapshotTime(),
                    o.getCeLtp(), o.getPeLtp()
            )).toList();

            AgentResponse technicalBias = technicalAgent.analyze(current);
            boolean isBullish = "BULLISH".equals(technicalBias.bias());
            boolean isBearish = "BEARISH".equals(technicalBias.bias());
            if (!isBullish && !isBearish) {
                continue; // neutral — no trade, same as live
            }

            TechnicalAgent.TechnicalFeatures features = technicalAgent.getFeatures(current, allSnapshots);
            double modelBullish = onnxModelService.predictBullishProbability(
                    features.rsi(), features.spotToEma20(), features.ema20ToEma50(), features.vix(),
                    features.prevDailyReturn(), features.bbWidth(), features.macdHist(), features.volumeRatio());
            double modelConfidence = isBullish ? modelBullish : 100.0 - modelBullish;
            double agentConfidence = confidenceEngine
                    .calculateRawConfidence(current, optionDtos, spotChange, isBullish).rawConfidence();
            double blended = (modelWeight * modelConfidence) + ((1.0 - modelWeight) * agentConfidence);

            CriticAgent.CriticResult criticResult =
                    criticAgent.evaluateAndApplyPenalties(blended, current, optionChain, isBullish);

            if (criticResult.adjustedConfidence() < gatingThreshold) {
                continue;
            }

            String signalType = isBullish ? "BUY_CE" : "BUY_PE";
            int strike = ((int) Math.round(spot / 50.0)) * 50;
            double target1 = round2(entryPremium * (1.0 + targetProfitPercent / 200.0));
            double target2 = round2(entryPremium * (1.0 + targetProfitPercent / 100.0));
            double stopLoss = round2(entryPremium * (1.0 - stopLossPercent / 100.0));

            TradeSignal signal = new TradeSignal();
            signal.setSignalTime(current.getSnapshotTime());
            signal.setSignalType(signalType);
            signal.setStrike(strike);
            signal.setEntry(entryPremium);
            signal.setQuantity(lotSize);
            signal.setStopLoss(stopLoss);
            signal.setTarget1(target1);
            signal.setTarget2(target2);
            signal.setConfidence(round2(criticResult.adjustedConfidence()));
            signal.setStatus("ACTIVE");
            tradeSignalRepository.save(signal);
            totalSignals++;

            activeTrades.add(new ActiveSimulatedTrade(signal, isBullish, spot, current.getSnapshotTime(),
                    entryPremium, stopLoss, target1, target2, lotSize));
        }

        // Close any still-open trades at their last mark-to-market value.
        MarketSnapshot lastSnap = snapshots.get(snapshots.size() - 1);
        for (ActiveSimulatedTrade trade : activeTrades) {
            double price = markToMarket(trade, lastSnap.getNiftySpot(), lastSnap.getSnapshotTime());
            Outcome o = closeTrade(trade, "EXPIRED", price, lastSnap.getSnapshotTime(), 0.0);
            grossPnl += o.grossInr;
            totalCosts += o.costInr;
            totalHoldingSeconds += o.holdingSeconds;
            tradeNetPnls.add(o.grossInr - o.costInr);
            expiredCount++;
        }

        double netPnl = grossPnl - totalCosts;
        double winRate = totalSignals > 0 ? (double) target2Hits / totalSignals * 100.0 : 0.0;
        long avgHolding = totalSignals > 0 ? totalHoldingSeconds / totalSignals : 0L;

        Map<String, Object> results = new HashMap<>();
        results.put("status", "SUCCESS");
        results.put("totalSignals", totalSignals);
        results.put("target1Touches", target1Touches);
        results.put("target2Hits", target2Hits);
        results.put("stopLossHits", stopLossHits);
        results.put("expired", expiredCount);
        results.put("grossPnlInr", round2(grossPnl));
        results.put("totalCostsInr", round2(totalCosts));
        results.put("netPnlInr", round2(netPnl));
        results.put("winRatePercentage", round2(winRate)); // target-2 hit rate (kept for back-compat)
        results.put("avgHoldingSeconds", avgHolding);

        // Risk metrics (NET of costs) — the real measure of edge.
        BacktestMetrics m = computeMetrics(tradeNetPnls);
        results.put("netWinRatePercentage", m.winRatePct()); // % of trades with positive NET P&L
        results.put("avgWinInr", m.avgWin());
        results.put("avgLossInr", m.avgLoss());
        results.put("expectancyInr", m.expectancy()); // avg NET P&L per trade — must be > 0 to be viable
        results.put("profitFactor", m.profitFactor()); // gross profit / gross loss; > 1 = profitable
        results.put("maxDrawdownInr", m.maxDrawdown());

        log.info("Backtest complete: Signals={}, Wins={}, SL={}, Expired={}, Net P&L={} INR (gross={}, costs={}), Win Rate={}%",
                totalSignals, target2Hits, stopLossHits, expiredCount,
                results.get("netPnlInr"), results.get("grossPnlInr"), results.get("totalCostsInr"),
                results.get("winRatePercentage"));
        return results;
    }

    /** Approximate the current option premium: 0.5 delta on spot move, minus time decay. */
    private double markToMarket(ActiveSimulatedTrade trade, double spot, LocalDateTime nowTime) {
        double intrinsic = trade.isCall
                ? (spot - trade.entrySpot) * 0.5
                : (trade.entrySpot - spot) * 0.5;
        double holdingHours = Duration.between(trade.entryTime, nowTime).toSeconds() / 3600.0;
        double theta = thetaDecayPerHour * Math.max(0.0, holdingHours);
        return round2(trade.entryPremium + intrinsic - theta);
    }

    /** Closes a trade at the given exit level, applying slippage + brokerage, and persists the result. */
    private Outcome closeTrade(ActiveSimulatedTrade trade, String outcome, double exitLevel,
                               LocalDateTime closeTime, double accuracy) {
        // Slippage: buy slightly higher, sell slightly lower than the nominal level.
        double entryFill = trade.entryPremium * (1.0 + slippagePercent / 100.0);
        double exitFill = exitLevel * (1.0 - slippagePercent / 100.0);

        double grossInr = (exitFill - entryFill) * trade.quantity;
        double slippageCostInr = ((trade.entryPremium * slippagePercent / 100.0)
                + (exitLevel * slippagePercent / 100.0)) * trade.quantity;
        double costInr = slippageCostInr + brokeragePerTrade;
        long holdingSeconds = Duration.between(trade.signal.getSignalTime(), closeTime).toSeconds();

        trade.signal.setStatus("EXPIRED".equals(outcome) ? "EXPIRED" : ("STOP_LOSS".equals(outcome) ? "FAILED" : "COMPLETED"));
        tradeSignalRepository.save(trade.signal);

        TradeResult result = new TradeResult();
        result.setSignal(trade.signal);
        result.setOutcome(outcome);
        result.setProfitLoss(round2(grossInr - brokeragePerTrade)); // net of brokerage (slippage already in fills)
        result.setHoldingTime(holdingSeconds);
        result.setAccuracy(accuracy);
        tradeResultRepository.save(result);

        log.info("Simulated trade closed: ID={}, Outcome={}, NetP&L={} INR", trade.signal.getId(), outcome,
                result.getProfitLoss());
        return new Outcome(grossInr, costInr, holdingSeconds);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Risk metrics over per-trade NET P&L (close-order). Pure + unit-tested. */
    public record BacktestMetrics(int trades, int wins, int losses, double winRatePct,
                                  double avgWin, double avgLoss, double expectancy,
                                  double profitFactor, double maxDrawdown) {}

    public static BacktestMetrics computeMetrics(List<Double> netPnls) {
        int trades = netPnls.size();
        int wins = 0, losses = 0;
        double grossProfit = 0.0, grossLoss = 0.0;
        double equity = 0.0, peak = 0.0, maxDd = 0.0;
        for (double p : netPnls) {
            if (p > 0) { wins++; grossProfit += p; }
            else if (p < 0) { losses++; grossLoss += -p; }
            equity += p;
            if (equity > peak) peak = equity;
            double dd = peak - equity;
            if (dd > maxDd) maxDd = dd;
        }
        double net = grossProfit - grossLoss;
        double winRate = trades > 0 ? 100.0 * wins / trades : 0.0;
        double avgWin = wins > 0 ? grossProfit / wins : 0.0;
        double avgLoss = losses > 0 ? grossLoss / losses : 0.0; // positive magnitude
        double expectancy = trades > 0 ? net / trades : 0.0;
        // 999 = "no losing trades" sentinel (avoids JSON infinity).
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? 999.0 : 0.0);
        return new BacktestMetrics(trades, wins, losses, round2(winRate), round2(avgWin),
                round2(avgLoss), round2(expectancy), round2(profitFactor), round2(maxDd));
    }

    private record Outcome(double grossInr, double costInr, long holdingSeconds) {}

    private static class ActiveSimulatedTrade {
        final TradeSignal signal;
        final boolean isCall;
        final double entrySpot;
        final LocalDateTime entryTime;
        final double entryPremium;
        final double stopLoss;
        final double target1;
        final double target2;
        final int quantity;
        boolean hitTarget1 = false;

        ActiveSimulatedTrade(TradeSignal signal, boolean isCall, double entrySpot, LocalDateTime entryTime,
                             double entryPremium, double stopLoss, double target1, double target2, int quantity) {
            this.signal = signal;
            this.isCall = isCall;
            this.entrySpot = entrySpot;
            this.entryTime = entryTime;
            this.entryPremium = entryPremium;
            this.stopLoss = stopLoss;
            this.target1 = target1;
            this.target2 = target2;
            this.quantity = quantity;
        }
    }
}
