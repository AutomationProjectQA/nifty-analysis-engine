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

        // 1. Check for High Volatility
        if (vix > 18.0) {
            comments.add("High volatility regime detected (VIX = " + vix + ")");
            return new AgentResponse(40.0, "HIGH_VOLATILITY", comments);
        }

        // 2. Check for Sideways / Range-bound Market
        if (history.size() >= 15) {
            double mean = history.stream().mapToDouble(MarketSnapshot::getNiftySpot).average().orElse(spot);
            double sumSquares = history.stream()
                    .mapToDouble(s -> Math.pow(s.getNiftySpot() - mean, 2))
                    .sum();
            double stdDev = Math.sqrt(sumSquares / history.size());

            double atr = calculateAtr(instrument, evaluationTime);
            double sidewaysThreshold = sidewaysAtrFactor * atr;

            // If price standard deviation over last 15-30 snapshots is less than 0.25 * ATR, it is sideways
            if (stdDev < sidewaysThreshold) {
                comments.add(String.format("Sideways/Consolidation regime detected (StdDev = %.2f < ATR Threshold = %.2f)",
                        stdDev, sidewaysThreshold));
                return new AgentResponse(50.0, "SIDEWAYS", comments);
            }
        }

        // 3. Determine Trending Regimes
        Double ema20 = latest.getEma20();
        Double ema50 = latest.getEma50();

        if (ema20 != null && ema50 != null) {
            if (spot > ema20 && ema20 > ema50) {
                comments.add("Trending Bullish regime (Spot > EMA20 > EMA50)");
                return new AgentResponse(85.0, "TRENDING_BULLISH", comments);
            } else if (spot < ema20 && ema20 < ema50) {
                comments.add("Trending Bearish regime (Spot < EMA20 < EMA50)");
                return new AgentResponse(15.0, "TRENDING_BEARISH", comments);
            }
        }

        comments.add("Neutral/Transition regime detected");
        return new AgentResponse(50.0, "NEUTRAL", comments);
    }

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
