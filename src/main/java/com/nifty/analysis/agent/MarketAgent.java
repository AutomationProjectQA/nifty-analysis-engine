package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MarketAgent {

    public AgentResponse analyze(MarketSnapshot latest, Double prevSpot) {
        List<String> comments = new ArrayList<>();
        double spot = latest.getNiftySpot();
        double future = latest.getNiftyFuture();
        
        double premium = future - spot;
        double score = 50.0; // Baseline neutral

        // Evaluate Premium
        if (premium > 35.0) {
            score += 15.0;
            comments.add("Strong Futures Premium (+" + Math.round(premium * 100.0) / 100.0 + " pts) indicates long build-up");
        } else if (premium < 10.0) {
            score -= 10.0;
            comments.add("Narrow/Discount Futures Premium (" + Math.round(premium * 100.0) / 100.0 + " pts) indicates momentum fading");
        } else {
            comments.add("Standard Futures Premium (+" + Math.round(premium * 100.0) / 100.0 + " pts) detected");
        }

        // Evaluate Momentum
        if (prevSpot != null) {
            double spotChange = spot - prevSpot;
            if (spotChange > 10.0) {
                score += 15.0;
                comments.add("Bullish momentum tick: Nifty Spot rose by " + Math.round(spotChange * 100.0) / 100.0 + " pts");
            } else if (spotChange < -10.0) {
                score -= 15.0;
                comments.add("Bearish momentum tick: Nifty Spot fell by " + Math.round(-spotChange * 100.0) / 100.0 + " pts");
            }
        }

        // Bound score
        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : (score <= 40.0 ? "BEARISH" : "NEUTRAL");

        return new AgentResponse(score, bias, comments);
    }
}
