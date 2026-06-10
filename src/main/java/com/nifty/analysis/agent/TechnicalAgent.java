package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TechnicalAgent {

    private final TechnicalIndicatorService technicalIndicatorService;

    public record TechnicalFeatures(
        double rsi,
        double spotToEma20,
        double ema20ToEma50,
        double vix,
        double prevDailyReturn
    ) {}

    public TechnicalFeatures getFeatures(MarketSnapshot latest) {
        double rsi = latest.getRsi() != null ? latest.getRsi() : 50.0;
        double spotToEma20 = (latest.getEma20() != null && latest.getEma20() > 0) ? latest.getNiftySpot() / latest.getEma20() : 1.0;
        double ema20ToEma50 = (latest.getEma20() != null && latest.getEma50() != null && latest.getEma50() > 0) ? latest.getEma20() / latest.getEma50() : 1.0;
        double vix = latest.getIndiaVix() != null ? latest.getIndiaVix() : 15.0;
        double prevReturn = technicalIndicatorService.calculateYesterdayDailyReturn(latest.getSnapshotTime());
        
        return new TechnicalFeatures(rsi, spotToEma20, ema20ToEma50, vix, prevReturn);
    }

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
            if (spot >= vwap) {
                score += 15.0;
                comments.add("Nifty Spot trading above/at daily VWAP support (" + vwap + ")");
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
