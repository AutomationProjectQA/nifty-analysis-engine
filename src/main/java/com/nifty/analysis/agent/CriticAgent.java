package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CriticAgent {

    private final EventRiskAgent eventRiskAgent;

    public CriticResult evaluateAndApplyPenalties(
            double rawConfidence, 
            MarketSnapshot latest, 
            List<OptionSnapshot> optionChain, 
            boolean isCall
    ) {
        log.info("Critic Agent attacking candidate trade setup (Raw Confidence = {}%)...", rawConfidence);
        
        List<PenaltyDetails> penalties = new ArrayList<>();
        double adjustedConfidence = rawConfidence;

        // 1. Penalty: Overbought RSI exhaustion (RSI > 70.0)
        Double rsi = latest.getRsi();
        if (rsi != null && rsi > 70.0) {
            double penalty = 10.0;
            adjustedConfidence -= penalty;
            penalties.add(new PenaltyDetails("RSI", -penalty, "Overbought RSI (" + rsi + ") indicating exhaustion risk"));
        }

        // 2. Penalty: High Volatility Option Decay (VIX > 18.0)
        double vix = latest.getIndiaVix();
        if (vix > 18.0) {
            double penalty = 10.0;
            adjustedConfidence -= penalty;
            penalties.add(new PenaltyDetails("VIX", -penalty, "High VIX (" + vix + ") increases option premium decay speed"));
        }

        // 3. Penalty: Same-Day High Impact Macroeconomic Event
        AgentResponse eventRisk = eventRiskAgent.evaluateCurrentRisk(latest.getSnapshotTime().toLocalDate());
        if ("EVENT_DRIVEN".equals(eventRisk.bias())) {
            double penalty = 30.0;
            adjustedConfidence -= penalty;
            penalties.add(new PenaltyDetails("Event Risk", -penalty, "Same-day scheduled high-impact economic news creates extreme risk"));
        }

        // 4. Penalty: Opposing Writing Concentration (Heavy Call writing for CE trade, or Put writing for PE trade)
        int atmStrike = ((int) Math.round(latest.getNiftySpot() / 50.0)) * 50;
        long totalCeOiChange = 0;
        long totalPeOiChange = 0;

        for (OptionSnapshot strike : optionChain) {
            if (Math.abs(strike.getStrikePrice() - atmStrike) <= 50) {
                totalCeOiChange += strike.getCeOiChange() != null ? strike.getCeOiChange() : 0L;
                totalPeOiChange += strike.getPeOiChange() != null ? strike.getPeOiChange() : 0L;
            }
        }

        if (isCall && totalCeOiChange > totalPeOiChange && totalCeOiChange > 100000) {
            double penalty = 15.0;
            adjustedConfidence -= penalty;
            penalties.add(new PenaltyDetails("OI Resistance", -penalty, "Heavy Call writing building up at nearby strikes creates overhead barrier"));
        } else if (!isCall && totalPeOiChange > totalCeOiChange && totalPeOiChange > 100000) {
            double penalty = 15.0;
            adjustedConfidence -= penalty;
            penalties.add(new PenaltyDetails("OI Support", -penalty, "Heavy Put writing building up at nearby strikes creates downside support (bearish headwind)"));
        }

        adjustedConfidence = Math.max(0.0, Math.round(adjustedConfidence * 100.0) / 100.0);
        log.info("Critic analysis complete. Adjusted Confidence: {}%", adjustedConfidence);

        return new CriticResult(adjustedConfidence, penalties);
    }

    public record PenaltyDetails(
        String factor,
        double scoreAdjustment,
        String comment
    ) {}

    public record CriticResult(
        double adjustedConfidence,
        List<PenaltyDetails> appliedPenalties
    ) {}
}
