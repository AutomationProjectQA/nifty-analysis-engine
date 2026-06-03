package com.nifty.analysis.backtest;

import com.nifty.analysis.agent.DecisionAgent;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Transactional
    public Map<String, Object> runBacktest(LocalDateTime start, LocalDateTime end) {
        log.info("Starting historical backtesting simulation from {} to {}...", start, end);
        
        // Fetch all market snapshots in the backtest range ordered chronologically
        List<MarketSnapshot> snapshots = marketSnapshotRepository.findAll().stream()
                .filter(s -> !s.getSnapshotTime().isBefore(start) && !s.getSnapshotTime().isAfter(end))
                .sorted((s1, s2) -> s1.getSnapshotTime().compareTo(s2.getSnapshotTime()))
                .toList();

        if (snapshots.size() < 2) {
            return Map.of("status", "FAILED", "error", "Insufficient historical market snapshots in range");
        }

        int totalSignals = 0;
        int target1Hits = 0;
        int target2Hits = 0;
        int stopLossHits = 0;
        double totalPnL = 0.0;

        List<ActiveSimulatedTrade> activeTrades = new ArrayList<>();

        for (int i = 1; i < snapshots.size(); i++) {
            MarketSnapshot current = snapshots.get(i);
            MarketSnapshot prev = snapshots.get(i - 1);
            
            double spot = current.getNiftySpot();
            double prevSpot = prev.getNiftySpot();
            double spotChange = spot - prevSpot;

            // 1. Update existing active simulated trades
            List<ActiveSimulatedTrade> completedTrades = new ArrayList<>();
            for (ActiveSimulatedTrade trade : activeTrades) {
                double currentOptionPrice;
                if (trade.isCall) {
                    // Call Option Delta approximation (0.5 delta)
                    currentOptionPrice = trade.entryPremium + (spot - trade.entrySpot) * 0.5;
                } else {
                    // Put Option Delta approximation (0.5 delta)
                    currentOptionPrice = trade.entryPremium + (trade.entrySpot - spot) * 0.5;
                }

                // Check Stop Loss
                if (currentOptionPrice <= trade.stopLoss) {
                    trade.signal.setStatus("FAILED");
                    
                    TradeResult result = new TradeResult();
                    result.setSignal(trade.signal);
                    result.setOutcome("STOP_LOSS");
                    result.setProfitLoss(trade.stopLoss - trade.entryPremium); // loss
                    result.setHoldingTime(java.time.Duration.between(trade.signal.getSignalTime(), current.getSnapshotTime()).toSeconds());
                    result.setAccuracy(0.0);
                    
                    tradeSignalRepository.save(trade.signal);
                    tradeResultRepository.save(result);
                    
                    totalPnL += result.getProfitLoss();
                    stopLossHits++;
                    completedTrades.add(trade);
                    log.info("Simulated Trade SL Hit: ID={}, Outcome={}", trade.signal.getId(), result.getOutcome());
                }
                // Check Target 2
                else if (currentOptionPrice >= trade.target2) {
                    trade.signal.setStatus("COMPLETED");
                    
                    TradeResult result = new TradeResult();
                    result.setSignal(trade.signal);
                    result.setOutcome("TARGET2");
                    result.setProfitLoss(trade.target2 - trade.entryPremium); // max profit
                    result.setHoldingTime(java.time.Duration.between(trade.signal.getSignalTime(), current.getSnapshotTime()).toSeconds());
                    result.setAccuracy(100.0);
                    
                    tradeSignalRepository.save(trade.signal);
                    tradeResultRepository.save(result);
                    
                    totalPnL += result.getProfitLoss();
                    target2Hits++;
                    completedTrades.add(trade);
                    log.info("Simulated Trade Target 2 Hit: ID={}, Outcome={}", trade.signal.getId(), result.getOutcome());
                }
                // Check Target 1 (but let it run to see if it reaches Target 2)
                else if (currentOptionPrice >= trade.target1 && !trade.hitTarget1) {
                    trade.hitTarget1 = true;
                    target1Hits++;
                    log.info("Simulated Trade Target 1 Hit (still active): ID={}", trade.signal.getId());
                }
            }
            activeTrades.removeAll(completedTrades);

            // 2. Fetch Option snaps for the current time to check for fresh signals
            List<OptionSnapshot> optionChain = optionSnapshotRepository.findBySnapshotTime(current.getSnapshotTime());
            if (optionChain.isEmpty()) {
                continue;
            }

            List<OptionSnapshotDto> optionDtos = optionChain.stream().map(o -> new OptionSnapshotDto(
                    o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                    o.getIv(), o.getPcr(), o.getMaxPain(), o.getSnapshotTime()
            )).toList();

            // Evaluate signal
            double rawConf = confidenceEngine.calculateRawConfidence(current, optionDtos, spotChange).rawConfidence();
            boolean isBullishBias = current.getEma20() != null && current.getEma50() != null && current.getNiftySpot() > current.getEma20();
            
            CriticAgent.CriticResult criticResult = criticAgent.evaluateAndApplyPenalties(rawConf, current, optionChain, isBullishBias);

            if (criticResult.adjustedConfidence() >= 80.0) {
                // Generate signal
                String signalType = isBullishBias ? "BUY_CE" : "BUY_PE";
                int strike = ((int) Math.round(spot / 50.0)) * 50;

                TradeSignal signal = new TradeSignal();
                signal.setSignalTime(current.getSnapshotTime());
                signal.setSignalType(signalType);
                signal.setStrike(strike);
                signal.setEntry(150.0);
                signal.setStopLoss(135.0);
                signal.setTarget1(165.0);
                signal.setTarget2(180.0);
                signal.setConfidence(criticResult.adjustedConfidence());
                signal.setStatus("ACTIVE");

                tradeSignalRepository.save(signal);
                totalSignals++;

                activeTrades.add(new ActiveSimulatedTrade(
                        signal,
                        isBullishBias,
                        spot,
                        150.0,
                        135.0,
                        165.0,
                        180.0
                ));
                log.info("Simulated Trade Triggered at backtest time {}: Strike={} {}", current.getSnapshotTime(), strike, signalType);
            }
        }

        // Close remaining active trades as EXPIRED
        for (ActiveSimulatedTrade trade : activeTrades) {
            trade.signal.setStatus("FAILED");
            
            TradeResult result = new TradeResult();
            result.setSignal(trade.signal);
            result.setOutcome("EXPIRED");
            result.setProfitLoss(0.0);
            result.setHoldingTime(0L);
            result.setAccuracy(0.0);
            
            tradeSignalRepository.save(trade.signal);
            tradeResultRepository.save(result);
        }

        double winRate = totalSignals > 0 ? (double) (target1Hits + target2Hits) / (totalSignals * 2) * 100.0 : 0.0;
        
        Map<String, Object> results = new HashMap<>();
        results.put("status", "SUCCESS");
        results.put("totalSignals", totalSignals);
        results.put("target1Hits", target1Hits);
        results.put("target2Hits", target2Hits);
        results.put("stopLossHits", stopLossHits);
        results.put("totalPnL", totalPnL);
        results.put("winRatePercentage", Math.round(winRate * 100.0) / 100.0);

        log.info("Backtest complete: Total Signals={}, Win Rate={}%, Net P&L={}", totalSignals, results.get("winRatePercentage"), totalPnL);
        return results;
    }

    private static class ActiveSimulatedTrade {
        TradeSignal signal;
        boolean isCall;
        double entrySpot;
        double entryPremium;
        double stopLoss;
        double target1;
        double target2;
        boolean hitTarget1 = false;

        ActiveSimulatedTrade(TradeSignal signal, boolean isCall, double entrySpot, double entryPremium, double stopLoss, double target1, double target2) {
            this.signal = signal;
            this.isCall = isCall;
            this.entrySpot = entrySpot;
            this.entryPremium = entryPremium;
            this.stopLoss = stopLoss;
            this.target1 = target1;
            this.target2 = target2;
        }
    }
}
