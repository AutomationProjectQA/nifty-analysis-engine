package com.nifty.analysis.engine;

import com.nifty.analysis.agent.MarketRegimeAgent;
import com.nifty.analysis.agent.OptionsAgent;
import com.nifty.analysis.agent.SentimentAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.agent.MultiTimeframeAgent;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import com.nifty.analysis.service.OptionsIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfidenceEngineTest {

    @Mock
    private ConfidenceWeightRepository confidenceWeightRepository;
    @Mock
    private MarketRegimeAgent marketRegimeAgent;
    @Mock
    private TechnicalAgent technicalAgent;
    @Mock
    private OptionsAgent optionsAgent;
    @Mock
    private SentimentAgent sentimentAgent;
    @Mock
    private OptionsIndicatorService optionsIndicatorService;
    @Mock
    private MultiTimeframeAgent multiTimeframeAgent;

    private ConfidenceEngine confidenceEngine;

    @BeforeEach
    void setUp() {
        confidenceEngine = new ConfidenceEngine(
                confidenceWeightRepository,
                marketRegimeAgent,
                technicalAgent,
                optionsAgent,
                sentimentAgent,
                optionsIndicatorService,
                multiTimeframeAgent
        );
    }

    @Test
    void testCalculateRawConfidence_UsesOverallPcr() {
        // Arrange — pin to a Tuesday (Nifty weekly expiry) so DTE=0 and the futures basis is deterministic.
        LocalDateTime now = LocalDateTime.of(2026, 6, 23, 11, 0);
        MarketSnapshot latest = new MarketSnapshot();
        latest.setSnapshotTime(now);
        latest.setNiftySpot(23500.0);
        latest.setNiftyFuture(23530.0);
        latest.setRsi(50.0);
        latest.setVwap(23500.0);
        latest.setIndiaVix(12.0);

        OptionSnapshotDto s1 = new OptionSnapshotDto(23400, 10000L, 50000L, 0L, 0L, 12.0, 5.0, 23500.0, 1000L, 2000L, now);
        OptionSnapshotDto s2 = new OptionSnapshotDto(23500, 80000L, 70000L, 0L, 0L, 12.0, 0.875, 23500.0, 1000L, 2000L, now);
        List<OptionSnapshotDto> chain = List.of(s1, s2);

        when(confidenceWeightRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(marketRegimeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(optionsAgent.analyze(chain, 23500.0, 10.0)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(sentimentAgent.analyze()).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(multiTimeframeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        
        // Mock overall PCR calculation to return a value that maps to a specific PCR score
        // We test BEARISH direction (isCall = false)
        when(optionsIndicatorService.calculateOverallPcr(chain)).thenReturn(0.6); // Should map to 100.0 PCR score for BEARISH

        // Act
        ConfidenceEngine.RawConfidenceResult result = confidenceEngine.calculateRawConfidence(latest, chain, 10.0, false);

        // Assert
        // Let's trace expected factor scores for BEARISH:
        // Fallback weights (DB empty): Trend15, MTF15, OI15, PCR15, VWAP15, RSI10, Futures7, Sentiment8.
        // BEARISH factor scores:
        // Trend=50, MTF=50, OI=50, PCR(0.6<=0.7)=100, VWAP(23500<23500=false)=0, RSI(50)=50, Sentiment=50.
        // Futures (P3-4): premium=30 vs fair basis at DTE=0 (~2.1, band 8) → not a discount → 0.
        // Sum = 50*15+50*15+50*15+100*15+0*15+50*10+0*7+50*8 = 4650 → /100 = 46.5%.
        assertEquals(46.5, result.rawConfidence(), 0.01);
        assertEquals(100.0, result.factorScores().get("PCR"));
        verify(optionsIndicatorService, times(1)).calculateOverallPcr(chain);
    }

    @Test
    void blendConfidence_decollinearizesTrendGroup() {
        // Equal weights (sum 100); only the three collinear trend factors fire.
        Map<String, Double> weights = Map.of(
                "Trend", 15.0, "MultiTimeframe", 15.0, "VWAP", 15.0, "OI", 15.0,
                "PCR", 15.0, "RSI", 10.0, "Futures", 7.0, "Sentiment", 8.0);
        Map<String, Double> scores = Map.of(
                "Trend", 100.0, "MultiTimeframe", 100.0, "VWAP", 100.0, "OI", 0.0,
                "PCR", 0.0, "RSI", 0.0, "Futures", 0.0, "Sentiment", 0.0);

        // Naive: trend counted 3x → 45% of the score from one signal.
        assertEquals(45.0, ConfidenceEngine.blendConfidence(scores, weights, false), 0.01);
        // De-collinearized: trend counted ONCE (weight 45/3=15 over total 70) → ~21.43%.
        assertEquals(21.43, ConfidenceEngine.blendConfidence(scores, weights, true), 0.01);
    }

    @Test
    void futuresBasisScore_normalizesByDaysToExpiry() {
        double spot = 23500.0;
        // Near expiry (DTE=1): fair ~4.2 → a +30 basis is clearly rich → bullish.
        assertEquals(100.0, ConfidenceEngine.futuresBasisScore(30.0, spot, 1, true), 0.01);
        // Far from expiry (DTE=6): fair ~25, band ~12.5 → the SAME +30 is only neutral.
        assertEquals(50.0, ConfidenceEngine.futuresBasisScore(30.0, spot, 6, true), 0.01);
        // A clear discount is bearish at any DTE.
        assertEquals(100.0, ConfidenceEngine.futuresBasisScore(-40.0, spot, 3, false), 0.01);
    }

    @Test
    void blendConfidence_allFactorsAgree_is100_eitherWay() {
        Map<String, Double> weights = Map.of(
                "Trend", 15.0, "MultiTimeframe", 15.0, "VWAP", 15.0, "OI", 15.0,
                "PCR", 15.0, "RSI", 10.0, "Futures", 7.0, "Sentiment", 8.0);
        Map<String, Double> scores = Map.of(
                "Trend", 100.0, "MultiTimeframe", 100.0, "VWAP", 100.0, "OI", 100.0,
                "PCR", 100.0, "RSI", 100.0, "Futures", 100.0, "Sentiment", 100.0);
        assertEquals(100.0, ConfidenceEngine.blendConfidence(scores, weights, false), 0.01);
        assertEquals(100.0, ConfidenceEngine.blendConfidence(scores, weights, true), 0.01);
    }
}
