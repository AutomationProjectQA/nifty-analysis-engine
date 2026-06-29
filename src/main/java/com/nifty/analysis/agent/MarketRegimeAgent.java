package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketCandleRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketRegimeAgent {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final MarketCandleRepository marketCandleRepository;

    // Sideways-detection sensitivity: a regime is flagged SIDEWAYS when recent price
    // std-dev falls below this fraction of ATR. Lower = fewer markets flagged sideways
    // (the original 0.25 was aggressive and silently blocked most trades).
    @Value("${nifty.regime.sideways-atr-factor:0.10}")
    private double sidewaysAtrFactor;

    public AgentResponse analyze() {
        return analyze("NIFTY", LocalDateTime.now());
    }

    public AgentResponse analyze(String instrument, LocalDateTime evaluationTime) {
        List<MarketSnapshot> history = marketSnapshotRepository.findHistoryBeforeByInstrument(
                instrument,
                evaluationTime,
                PageRequest.of(0, 30));

        List<String> comments = new ArrayList<>();
        if (history.isEmpty()) {
            return new AgentResponse(50.0, "NEUTRAL", List.of("No market snapshot history available"));
        }

        MarketSnapshot latest = history.getFirst();
        double spot = latest.getNiftySpot();
        double vix = latest.getIndiaVix();
        Double ema20 = latest.getEma20();
        Double ema50 = latest.getEma50();
        Double vwap = latest.getVwap();

        // CONTINUOUS trend score (Phase-1 AG-F1/F2): 50 = neutral, >50 bullish, <50 bearish.
        // Built from spot-vs-EMA20 and EMA20-vs-EMA50 separation rather than a strict 3-way ordering
        // (which is unreachable on cold start when EMA20≈EMA50). Falls back to spot-vs-VWAP/EMA20
        // while the long EMA is still warming up so a real early trend isn't scored a flat 50.
        double score;
        boolean emasWarm = ema20 != null && ema50 != null && Math.abs(ema20 - ema50) / ema50 > 0.0005;
        if (emasWarm) {
            double pos = (spot - ema20) / ema20;       // spot above/below short EMA
            double sep = (ema20 - ema50) / ema50;       // short above/below long EMA (trend)
            score = clampScore(50.0 + (pos + sep) / 0.006 * 50.0);
        } else {
            double ref = (vwap != null && vwap > 0) ? vwap : (ema20 != null ? ema20 : spot);
            double pos = ref > 0 ? (spot - ref) / ref : 0.0;
            score = clampScore(50.0 + pos / 0.003 * 50.0);
            comments.add("Trend EMAs warming up — using spot-vs-" + (vwap != null ? "VWAP" : "EMA20") + " fallback.");
        }

        // Breakout: a clean break of the recent range is a STRONG trend (boost the score). Labelled
        // TRENDING so strategy selection stays sensible; the boost is what matters for confidence.
        boolean breakout = false;
        if (history.size() >= 10) {
            double recentHigh = history.stream().skip(1).mapToDouble(MarketSnapshot::getNiftySpot).max().orElse(spot);
            double recentLow = history.stream().skip(1).mapToDouble(MarketSnapshot::getNiftySpot).min().orElse(spot);
            double atr = calculateAtr(instrument, evaluationTime);
            if (spot > recentHigh + 0.1 * atr) {
                score = Math.max(score, 82.0);
                breakout = true;
                comments.add(String.format("BREAKOUT above recent range high %.1f (ATR %.1f).", recentHigh, atr));
            } else if (spot < recentLow - 0.1 * atr) {
                score = Math.min(score, 18.0);
                breakout = true;
                comments.add(String.format("BREAKDOWN below recent range low %.1f (ATR %.1f).", recentLow, atr));
            }
        }

        // High volatility is a LABEL (drives strategy selection) but must NOT also tank the trend
        // score — VIX is already penalised separately by the CriticAgent (Phase-1 AG-F3, avoid
        // double counting). A strong trend in a volatile market keeps its high score.
        if (vix > 18.0) {
            comments.add("High volatility regime (VIX = " + vix + "); trend score retained at " + round1(score) + ".");
            return new AgentResponse(score, "HIGH_VOLATILITY", comments);
        }

        // Sideways: low realised dispersion vs ATR. Keep the (near-neutral) continuous score.
        if (!breakout && history.size() >= 15) {
            double mean = history.stream().mapToDouble(MarketSnapshot::getNiftySpot).average().orElse(spot);
            double stdDev = Math.sqrt(history.stream().mapToDouble(s -> Math.pow(s.getNiftySpot() - mean, 2)).sum() / history.size());
            double sidewaysThreshold = sidewaysAtrFactor * calculateAtr(instrument, evaluationTime);
            if (stdDev < sidewaysThreshold) {
                comments.add(String.format("Sideways/Consolidation regime (StdDev %.2f < threshold %.2f).", stdDev, sidewaysThreshold));
                return new AgentResponse(score, "SIDEWAYS", comments);
            }
        }

        String label;
        if (score >= 60.0) label = "TRENDING_BULLISH";
        else if (score <= 40.0) label = "TRENDING_BEARISH";
        else label = "NEUTRAL";
        comments.add((breakout ? "Breakout " : "") + label + " (trend score " + round1(score) + ").");
        return new AgentResponse(score, label, comments);
    }

    private static double clampScore(double v) { return Math.max(0.0, Math.min(100.0, Math.round(v * 100.0) / 100.0)); }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private double calculateAtr(String instrument, LocalDateTime evaluationTime) {
        try {
            List<MarketCandle> candles = marketCandleRepository.findHistoryBeforeByInstrument(
                    instrument,
                    "5m",
                    evaluationTime,
                    PageRequest.of(0, 15));
            if (candles.size() < 2) {
                return 15.0; // Default fallback
            }
            double totalTr = 0.0;
            int count = 0;
            for (int i = 0; i < candles.size() - 1; i++) {
                MarketCandle current = candles.get(i);
                MarketCandle previous = candles.get(i + 1);
                
                double tr = Math.max(current.getHigh() - current.getLow(),
                        Math.max(Math.abs(current.getHigh() - previous.getClose()),
                                 Math.abs(current.getLow() - previous.getClose())));
                totalTr += tr;
                count++;
                if (count >= 14) {
                    break;
                }
            }
            return count > 0 ? (totalTr / count) : 15.0;
        } catch (Exception e) {
            log.error("Failed to calculate ATR. Using fallback.", e);
            return 15.0;
        }
    }
}
