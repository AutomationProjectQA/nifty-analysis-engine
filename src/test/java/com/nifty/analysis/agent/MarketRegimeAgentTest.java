package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketCandleRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketRegimeAgentTest {

    @Mock private MarketSnapshotRepository marketSnapshotRepository;
    @Mock private MarketCandleRepository marketCandleRepository;

    @InjectMocks
    private MarketRegimeAgent marketRegimeAgent;

    private final LocalDateTime now = LocalDateTime.of(2026, 6, 25, 11, 0);

    @BeforeEach
    void setUp() {
        // No candle history -> calculateAtr() returns its 15.0 fallback, so the
        // sideways threshold is simply (factor * 15).
        lenient().when(marketCandleRepository.findHistoryBefore(eq("5m"), any(), any()))
                .thenReturn(List.of());
    }

    /** 15 snapshots alternating +/-0.5 around 23500 => std-dev ~0.5, VIX calm. */
    private List<MarketSnapshot> mildlyChoppyHistory() {
        List<MarketSnapshot> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            MarketSnapshot s = new MarketSnapshot();
            s.setSnapshotTime(now.minusMinutes(i));
            s.setNiftySpot(23500.0 + (i % 2 == 0 ? 0.5 : -0.5));
            s.setIndiaVix(12.0);
            history.add(s);
        }
        return history;
    }

    @Test
    void highVix_flaggedHighVolatility() {
        MarketSnapshot s = new MarketSnapshot();
        s.setSnapshotTime(now);
        s.setNiftySpot(23500.0);
        s.setIndiaVix(22.0); // > 18
        when(marketSnapshotRepository.findHistoryBefore(any(), any(Pageable.class))).thenReturn(List.of(s));

        AgentResponse response = marketRegimeAgent.analyze(now);

        assertEquals("HIGH_VOLATILITY", response.bias());
    }

    @Test
    void defaultFactor_flagsLowVolatilityAsSideways() {
        ReflectionTestUtils.setField(marketRegimeAgent, "sidewaysAtrFactor", 0.10); // threshold = 1.5 > 0.5
        when(marketSnapshotRepository.findHistoryBefore(any(), any(Pageable.class)))
                .thenReturn(mildlyChoppyHistory());

        AgentResponse response = marketRegimeAgent.analyze(now);

        assertEquals("SIDEWAYS", response.bias());
    }

    @Test
    void tightFactor_doesNotFlagSameMarketAsSideways() {
        ReflectionTestUtils.setField(marketRegimeAgent, "sidewaysAtrFactor", 0.001); // threshold = 0.015 < 0.5
        when(marketSnapshotRepository.findHistoryBefore(any(), any(Pageable.class)))
                .thenReturn(mildlyChoppyHistory()); // EMAs are null -> falls through to NEUTRAL

        AgentResponse response = marketRegimeAgent.analyze(now);

        assertEquals("NEUTRAL", response.bias());
    }

    @Test
    void trendingPrices_flaggedTrendingBullish() {
        ReflectionTestUtils.setField(marketRegimeAgent, "sidewaysAtrFactor", 0.10);
        List<MarketSnapshot> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            MarketSnapshot s = new MarketSnapshot();
            s.setSnapshotTime(now.minusMinutes(i));
            s.setNiftySpot(23540.0 - (i * 10.0)); // descending list => latest (first) is highest
            s.setIndiaVix(12.0);
            history.add(s);
        }
        // Latest snapshot: spot > ema20 > ema50 => trending bullish
        history.get(0).setEma20(23520.0);
        history.get(0).setEma50(23500.0);
        when(marketSnapshotRepository.findHistoryBefore(any(), any(Pageable.class))).thenReturn(history);

        AgentResponse response = marketRegimeAgent.analyze(now);

        assertEquals("TRENDING_BULLISH", response.bias());
    }
}
