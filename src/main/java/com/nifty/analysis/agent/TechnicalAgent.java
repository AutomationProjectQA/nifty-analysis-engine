package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TechnicalAgent {

    public AgentResponse analyze(MarketSnapshot latest) {
        List<String> comments = new ArrayList<>();
        double spot = latest.getNiftySpot();
        Double ema20 = latest.getEma20();
        Double ema50 = latest.getEma50();
        Double rsi = latest.getRsi();
        Double vwap = latest.getVwap();

        double score = 50.0;

        // 1. Evaluate EMA alignment
        if (ema20 != null && ema50 != null) {
            if (spot > ema20 && ema20 > ema50) {
                score += 15.0;
                comments.add("Bullish EMA Structure (Spot > EMA20 > EMA50)");
            } else if (spot < ema20 && ema20 < ema50) {
                score -= 15.0;
                comments.add("Bearish EMA Structure (Spot < EMA20 < EMA50)");
            }
        }

        // 2. Evaluate RSI
        if (rsi != null) {
            if (rsi >= 55.0 && rsi <= 68.0) {
                score += 15.0;
                comments.add("RSI (" + rsi + ") is in the strong bullish momentum zone");
            } else if (rsi > 70.0) {
                score += 5.0;
                comments.add("RSI (" + rsi + ") is overbought; watch for potential exhaustion");
            } else if (rsi < 40.0) {
                score -= 15.0;
                comments.add("RSI (" + rsi + ") is weak, indicating bearish dominance");
            }
        }

        // 3. Evaluate VWAP
        if (vwap != null && vwap > 0.0) {
            if (spot > vwap) {
                score += 15.0;
                comments.add("Nifty Spot trading above daily VWAP support (" + vwap + ")");
            } else {
                score -= 15.0;
                comments.add("Nifty Spot trading below daily VWAP resistance (" + vwap + ")");
            }
        }

        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : (score <= 40.0 ? "BEARISH" : "NEUTRAL");

        return new AgentResponse(score, bias, comments);
    }
}
