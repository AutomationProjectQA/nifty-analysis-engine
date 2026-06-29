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
        double prevDailyReturn,
        double bbWidth,
        double macdHist,
        double volumeRatio
    ) {}

    public TechnicalFeatures getFeatures(MarketSnapshot latest) {
        return getFeatures(latest, null);
    }

    public TechnicalFeatures getFeatures(MarketSnapshot latest, List<MarketSnapshot> allSnapshots) {
        return technicalIndicatorService.calculateHourlyFeatures(latest, allSnapshots);
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

        // 4. Engineered momentum/participation features (Phase-2 ML-P10-1): MACD histogram and
        // volume ratio were computed for the ML model but never used in the rule score. Add them so
        // the directional vote reflects momentum + participation, not just EMA/RSI/VWAP structure.
        try {
            TechnicalFeatures f = getFeatures(latest);
            if (f.macdHist() > 0) {
                score += 10.0;
                comments.add("MACD histogram positive (bullish momentum)");
            } else if (f.macdHist() < 0) {
                score -= 10.0;
                comments.add("MACD histogram negative (bearish momentum)");
            }
            if (f.volumeRatio() >= 1.2) {
                score += 5.0;
                comments.add("Above-average volume confirms participation (" + round2(f.volumeRatio()) + "x)");
            }
            // Bollinger squeeze (very low width) = range, not trend — pull an extreme score toward neutral.
            if (f.bbWidth() > 0 && f.bbWidth() < 0.01) {
                score = 50.0 + (score - 50.0) * 0.7;
                comments.add("Bollinger squeeze (low volatility) — reduced trend conviction");
            }
        } catch (Exception e) {
            comments.add("Engineered features unavailable for this tick");
        }

        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : (score <= 40.0 ? "BEARISH" : "NEUTRAL");

        return new AgentResponse(score, bias, comments);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
