package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EntryTimingAgent {

    public AgentResponse validateEntry(MarketSnapshot latest, Double prevSpot) {
        List<String> comments = new ArrayList<>();
        double spot = latest.getNiftySpot();
        Double vwap = latest.getVwap();
        
        if (vwap == null || vwap == 0.0) {
            return new AgentResponse(50.0, "NEUTRAL", List.of("VWAP not calculated. Skipping timing check."));
        }

        double score = 50.0;
        String bias = "NEUTRAL";

        // 1. VWAP Breakout confirmation (Price crossed VWAP from below)
        if (prevSpot != null && prevSpot <= vwap && spot > vwap) {
            score = 90.0;
            bias = "BULLISH";
            comments.add("VWAP Breakout Triggered: Nifty Spot crossed above VWAP (" + vwap + ")");
        }
        // 2. Pullback / VWAP Retest (Spot is just above VWAP within 0.15% margin)
        else if (spot >= vwap && (spot - vwap) / vwap <= 0.0015) {
            score = 85.0;
            bias = "BULLISH";
            comments.add("Pullback/Retest Entry Triggered: Nifty Spot trading near VWAP support (" + vwap + ")");
        }
        // 3. Extended / Chasing (Spot is > 0.5% above VWAP - overextended)
        else if (spot > vwap && (spot - vwap) / vwap > 0.005) {
            score = 30.0;
            bias = "NEUTRAL";
            comments.add("Overextended: Nifty Spot is currently chasing too far above VWAP (" + vwap + "). Wait for pullback.");
        } else {
            comments.add("No entry timing trigger active");
        }

        return new AgentResponse(score, bias, comments);
    }
}
