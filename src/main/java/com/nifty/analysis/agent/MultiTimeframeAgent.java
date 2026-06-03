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

    public AgentResponse analyze(LocalDateTime evaluationTime) {
        // Fetch last 12 candles before evaluation time
        List<MarketCandle> candles = marketCandleRepository.findHistoryBefore(
                "5m", 
                evaluationTime, 
                PageRequest.of(0, 12)
        );
        List<String> comments = new ArrayList<>();

        if (candles.isEmpty()) {
            return new AgentResponse(50.0, "NEUTRAL", List.of("No candles available for multi-timeframe check"));
        }

        // Check 5m Close vs Open
        MarketModel latest = mapCandle(candles.get(0));
        boolean is5mBullish = latest.close() > latest.open();
        comments.add("5m Candle: " + (is5mBullish ? "BULLISH" : "BEARISH"));

        // Check 15m (Requires 3 candles)
        boolean is15mBullish = false;
        if (candles.size() >= 3) {
            double open15m = candles.get(2).getOpen();
            double close15m = candles.get(0).getClose();
            is15mBullish = close15m > open15m;
            comments.add("15m Trend: " + (is15mBullish ? "BULLISH" : "BEARISH"));
        } else {
            comments.add("15m Trend: INSUFFICIENT DATA");
        }

        // Check 30m (Requires 6 candles)
        boolean is30mBullish = false;
        if (candles.size() >= 6) {
            double open30m = candles.get(5).getOpen();
            double close30m = candles.get(0).getClose();
            is30mBullish = close30m > open30m;
            comments.add("30m Trend: " + (is30mBullish ? "BULLISH" : "BEARISH"));
        } else {
            comments.add("30m Trend: INSUFFICIENT DATA");
        }

        // Check 60m (Requires 12 candles)
        boolean is60mBullish = false;
        if (candles.size() >= 12) {
            double open60m = candles.get(11).getOpen();
            double close60m = candles.get(0).getClose();
            is60mBullish = close60m > open60m;
            comments.add("60m Trend: " + (is60mBullish ? "BULLISH" : "BEARISH"));
        } else {
            comments.add("60m Trend: INSUFFICIENT DATA");
        }

        // Scoring based on trend alignments
        int bullishCount = 0;
        int totalChecked = 0;
        
        if (candles.size() >= 1) { bullishCount += is5mBullish ? 1 : 0; totalChecked++; }
        if (candles.size() >= 3) { bullishCount += is15mBullish ? 1 : 0; totalChecked++; }
        if (candles.size() >= 6) { bullishCount += is30mBullish ? 1 : 0; totalChecked++; }
        if (candles.size() >= 12) { bullishCount += is60mBullish ? 1 : 0; totalChecked++; }

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

    private MarketModel mapCandle(MarketCandle c) {
        return new MarketModel(c.getOpen(), c.getClose());
    }

    private record MarketModel(double open, double close) {}
}
