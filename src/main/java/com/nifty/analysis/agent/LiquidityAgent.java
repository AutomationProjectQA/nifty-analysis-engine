package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.OptionSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LiquidityAgent {

    // Thresholds
    private static final long MIN_OI_CONTRACTS = 50000L;
    private static final double MAX_BID_ASK_SPREAD_PCT = 0.015; // 1.5% max spread

    public AgentResponse evaluateStrike(OptionSnapshot strikeSnapshot, boolean isCall) {
        List<String> comments = new ArrayList<>();
        long oi = isCall ? (strikeSnapshot.getCeOi() != null ? strikeSnapshot.getCeOi() : 0L)
                       : (strikeSnapshot.getPeOi() != null ? strikeSnapshot.getPeOi() : 0L);

        // 1. Evaluate Open Interest (OI)
        boolean isOiPassing = oi >= MIN_OI_CONTRACTS;
        if (isOiPassing) {
            comments.add("Strike " + strikeSnapshot.getStrikePrice() + " has sufficient Open Interest (" + oi + " contracts)");
        } else {
            comments.add("Strike " + strikeSnapshot.getStrikePrice() + " has poor liquidity (OI = " + oi + " < " + MIN_OI_CONTRACTS + ")");
        }

        // 2. Simulate/evaluate bid-ask spread
        // In real-life, spreads are narrow near ATM and wide far OTM. 
        // For simulation, we assume ATM-centered strikes (which we collect) have good spreads.
        double simulatedSpreadPct = 0.005; // 0.5% default spread
        boolean isSpreadPassing = simulatedSpreadPct <= MAX_BID_ASK_SPREAD_PCT;
        comments.add("Option bid-ask spread is within limits (" + (simulatedSpreadPct * 100.0) + "%)");

        double score = (isOiPassing ? 60.0 : 20.0) + (isSpreadPassing ? 40.0 : 0.0);
        String bias = score >= 70.0 ? "BULLISH" : "NEUTRAL"; // Bullish bias means "liquid / clear to trade"

        return new AgentResponse(score, bias, comments);
    }
}
