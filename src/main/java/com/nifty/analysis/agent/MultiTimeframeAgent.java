package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.repository.MarketCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MultiTimeframeAgent {

    private final MarketCandleRepository marketCandleRepository;

    public AgentResponse analyze() {
        return analyze(LocalDateTime.now());
    }

    /** Higher timeframes checked for trend alignment, fastest → slowest. */
    private static final String[] TIMEFRAMES = {"5m", "15m", "30m", "60m"};

    public AgentResponse analyze(LocalDateTime evaluationTime) {
        // P5-4: read the REAL per-timeframe OHLC candles (15m/30m/60m tables that updateCandle
        // writes) instead of reconstructing higher timeframes by indexing into 5m candles — the
        // old approach assumed 12 gap-free consecutive 5m bars and broke across day boundaries,
        // lunch lulls and missing ticks.
        List<String> comments = new ArrayList<>();
        int bullishCount = 0;
        int totalChecked = 0;

        for (String tf : TIMEFRAMES) {
            List<MarketCandle> candles = marketCandleRepository.findHistoryBefore(
                    tf, evaluationTime, PageRequest.of(0, 1));
            if (candles.isEmpty() || candles.get(0).getOpen() == null || candles.get(0).getClose() == null) {
                comments.add(tf + " Trend: INSUFFICIENT DATA");
                continue;
            }
            MarketCandle latest = candles.get(0);
            boolean bullish = latest.getClose() > latest.getOpen();
            comments.add(tf + " Trend: " + (bullish ? "BULLISH" : "BEARISH"));
            bullishCount += bullish ? 1 : 0;
            totalChecked++;
        }

        if (totalChecked == 0) {
            return new AgentResponse(50.0, "NEUTRAL", List.of("No candles available for multi-timeframe check"));
        }

        double score;
        String bias;
        if (bullishCount == totalChecked) {
            score = 90.0;
            bias = "BULLISH";
            comments.add("Full Multi-Timeframe Trend Alignment (ALL GREEN)");
        } else if (bullishCount == 0) {
            score = 10.0;
            bias = "BEARISH";
            comments.add("Full Multi-Timeframe Trend Alignment (ALL RED)");
        } else {
            score = 50.0 + (double) (bullishCount - (totalChecked - bullishCount)) * 10.0;
            bias = score > 50.0 ? "BULLISH" : "BEARISH";
            comments.add("Timeframe conflict. Bullish/Bearish ratio: " + bullishCount + "/" + (totalChecked - bullishCount));
        }

        return new AgentResponse(score, bias, comments);
    }
}
