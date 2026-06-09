package com.nifty.analysis.service;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.entity.TradeReflection;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.TradeReflectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LlmServiceTest {

    private WebClient.Builder webClientBuilder;
    private TradeReflectionRepository tradeReflectionRepository;
    private MarketSnapshotRepository marketSnapshotRepository;
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        webClientBuilder = mock(WebClient.Builder.class);
        tradeReflectionRepository = mock(TradeReflectionRepository.class);
        marketSnapshotRepository = mock(MarketSnapshotRepository.class);
        llmService = new LlmService(webClientBuilder, tradeReflectionRepository, marketSnapshotRepository);
    }

    @Test
    void testGenerateTradeExplanation_FallbackTemplate() {
        // With missing API key, it should fallback to template explanation
        Map<String, Double> scores = new HashMap<>();
        scores.put("Trend", 70.0);
        String explanation = llmService.generateTradeExplanation("BUY_CE", 23200, 85.0, scores, "Bullish trend");

        assertNotNull(explanation);
        assertTrue(explanation.contains("85% confidence"));
        assertTrue(explanation.contains("bullish index momentum"));
    }

    @Test
    void testGeneratePostMortem_FallbackTemplate() {
        // Act
        TradeSignal signal = new TradeSignal();
        signal.setId(10L);
        signal.setSignalTime(LocalDateTime.now());
        signal.setSignalType("BUY_CE");
        signal.setStrike(23200);
        signal.setEntry(150.0);
        signal.setStopLoss(135.0);
        signal.setTarget1(165.0);
        signal.setTarget2(180.0);
        signal.setConfidence(85.0);

        llmService.generatePostMortem(signal, Collections.emptyList());

        // Assert that save is called with the fallback reflection text
        ArgumentCaptor<TradeReflection> captor = ArgumentCaptor.forClass(TradeReflection.class);
        verify(tradeReflectionRepository, times(1)).save(captor.capture());
        TradeReflection saved = captor.getValue();
        assertEquals(signal, saved.getSignal());
        assertTrue(saved.getReflectionText().contains("Likely caused by a sudden trend reversal"));
    }
}
