package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public AgentResponse analyze() {
        return analyze(LocalDateTime.now());
    }

    public AgentResponse analyze(LocalDateTime evaluationTime) {
        List<MarketSnapshot> history = marketSnapshotRepository.findHistoryBefore(
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

            // If price standard deviation over last 15-30 snapshots is less than 8.0 points, it is sideways
            if (stdDev < 8.0) {
                comments.add("Sideways/Consolidation regime detected (StdDev Nifty Spot = "
                        + Math.round(stdDev * 100.0) / 100.0 + ")");
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
}
