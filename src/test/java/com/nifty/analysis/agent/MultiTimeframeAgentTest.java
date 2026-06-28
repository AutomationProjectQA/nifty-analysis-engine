package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.repository.MarketCandleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiTimeframeAgentTest {

    @Mock private MarketCandleRepository marketCandleRepository;

    private final LocalDateTime now = LocalDateTime.of(2026, 6, 23, 11, 0);

    private MultiTimeframeAgent agent() {
        return new MultiTimeframeAgent(marketCandleRepository);
    }

    private static MarketCandle candle(double open, double close) {
        MarketCandle c = new MarketCandle();
        c.setOpen(open);
        c.setClose(close);
        return c;
    }

    private void stub(String tf, MarketCandle candle) {
        when(marketCandleRepository.findHistoryBeforeByInstrument(eq("NIFTY"), eq(tf), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(candle == null ? List.of() : List.of(candle));
    }

    @Test
    void allTimeframesBullish_fullAlignment() {
        for (String tf : new String[]{"5m", "15m", "30m", "60m"}) {
            stub(tf, candle(100.0, 110.0)); // close > open on every real HTF table
        }
        AgentResponse r = agent().analyze("NIFTY", now);
        assertEquals(90.0, r.score(), 0.01);
        assertEquals("BULLISH", r.bias());
    }

    @Test
    void allTimeframesBearish_fullAlignment() {
        for (String tf : new String[]{"5m", "15m", "30m", "60m"}) {
            stub(tf, candle(110.0, 100.0));
        }
        AgentResponse r = agent().analyze("NIFTY", now);
        assertEquals(10.0, r.score(), 0.01);
        assertEquals("BEARISH", r.bias());
    }

    @Test
    void missingHigherTimeframes_scoreOverAvailableOnly() {
        stub("5m", candle(100.0, 110.0));   // bullish
        stub("15m", candle(100.0, 110.0));  // bullish
        stub("30m", null);                   // insufficient
        stub("60m", null);                   // insufficient
        AgentResponse r = agent().analyze("NIFTY", now);
        // 2 of 2 available are bullish → full alignment among what exists
        assertEquals(90.0, r.score(), 0.01);
        assertEquals("BULLISH", r.bias());
        assertTrue(r.comments().stream().anyMatch(c -> c.contains("30m Trend: INSUFFICIENT")));
    }

    @Test
    void noCandles_neutral() {
        for (String tf : new String[]{"5m", "15m", "30m", "60m"}) {
            stub(tf, null);
        }
        AgentResponse r = agent().analyze("NIFTY", now);
        assertEquals(50.0, r.score(), 0.01);
        assertEquals("NEUTRAL", r.bias());
    }
}
