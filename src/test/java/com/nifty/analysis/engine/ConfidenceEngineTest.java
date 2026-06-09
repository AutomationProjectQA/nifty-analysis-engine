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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Arrange
        LocalDateTime now = LocalDateTime.now();
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
        when(marketRegimeAgent.analyze(now)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(optionsAgent.analyze(chain, 23500.0, 10.0)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(sentimentAgent.analyze()).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(multiTimeframeAgent.analyze(now)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        
        // Mock overall PCR calculation to return a value that maps to a specific PCR score
        // We test BEARISH direction (isCall = false)
        when(optionsIndicatorService.calculateOverallPcr(chain)).thenReturn(0.6); // Should map to 100.0 PCR score for BEARISH

        // Act
        ConfidenceEngine.RawConfidenceResult result = confidenceEngine.calculateRawConfidence(latest, chain, 10.0, false);

        // Assert
        // Let's trace expected factor scores for BEARISH:
        // Trend = 100 - 50.0 = 50.0 (weight 20)
        // OI = 100 - 50.0 = 50.0 (weight 20)
        // PCR = 0.6 <= 0.7 -> 100.0 (weight 15)
        // VWAP = Spot (23500) < VWAP (23500) -> 0.0 (weight 15) (unless we adjust the logic to <=)
        // RSI = 50.0 -> > 40 and <= 55 -> 50.0 (weight 10)
        // Futures = Premium (30.0) -> < 35.0 -> 50.0 (weight 10)
        // Sentiment = 100 - 50.0 = 50.0 (weight 10)
        // Total Weighted Sum = 50*20 + 50*20 + 100*15 + 0*15 + 50*10 + 50*10 + 50*10 = 1000 + 1000 + 1500 + 0 + 500 + 500 + 500 = 5000
        // Expected Raw Confidence = 5000 / 100 = 50.0%
        assertEquals(50.0, result.rawConfidence());
        assertEquals(100.0, result.factorScores().get("PCR"));
        verify(optionsIndicatorService, times(1)).calculateOverallPcr(chain);
    }
}
