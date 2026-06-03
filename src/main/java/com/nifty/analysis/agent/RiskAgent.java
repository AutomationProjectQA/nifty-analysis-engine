package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RiskAgent {

    public AgentResponse evaluateRisk(double entry, double stopLoss, double target1, double vix) {
        List<String> comments = new ArrayList<>();
        
        // 1. Calculate Risk to Reward
        double riskAmount = entry - stopLoss;
        double rewardAmount = target1 - entry;

        if (riskAmount <= 0) {
            return new AgentResponse(0.0, "BEARISH", List.of("Invalid Stop Loss: SL must be below entry."));
        }
        
        double rrRatio = rewardAmount / riskAmount;
        double score = 50.0;

        if (rrRatio >= 2.0) {
            score += 25.0;
            comments.add("Optimal Risk/Reward ratio (1:" + Math.round(rrRatio * 100.0) / 100.0 + ")");
        } else if (rrRatio >= 1.5) {
            score += 10.0;
            comments.add("Acceptable Risk/Reward ratio (1:" + Math.round(rrRatio * 100.0) / 100.0 + ")");
        } else {
            score -= 20.0;
            comments.add("Unfavorable Risk/Reward ratio (1:" + Math.round(rrRatio * 100.0) / 100.0 + "). Target 1:2.");
        }

        // 2. Evaluate Volatility Risk from VIX
        if (vix > 20.0) {
            score -= 15.0;
            comments.add("High Volatility risk (VIX = " + vix + "). Option buying premiums decay rapidly.");
        } else if (vix < 12.0) {
            comments.add("Low Volatility environment (VIX = " + vix + "). Premiums are cheap, favorable for option buying.");
        }

        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : "NEUTRAL"; // Bullish means risk is low / clear to trade

        return new AgentResponse(score, bias, comments);
    }
}
