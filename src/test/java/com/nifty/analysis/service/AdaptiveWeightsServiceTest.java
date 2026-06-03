package com.nifty.analysis.service;

import com.nifty.analysis.entity.ConfidenceWeight;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdaptiveWeightsServiceTest {

    @Mock
    private TradeResultRepository tradeResultRepository;
    @Mock
    private SignalExplanationRepository signalExplanationRepository;
    @Mock
    private ConfidenceWeightRepository confidenceWeightRepository;

    private AdaptiveWeightsService adaptiveWeightsService;

    @BeforeEach
    void setUp() {
        adaptiveWeightsService = new AdaptiveWeightsService(
                tradeResultRepository,
                signalExplanationRepository,
                confidenceWeightRepository
        );
    }

    @Test
    void testTuneWeightsNoHistory() {
        when(tradeResultRepository.findAll()).thenReturn(Collections.emptyList());
        
        adaptiveWeightsService.tuneWeights();
        
        verify(confidenceWeightRepository, never()).save(any(ConfidenceWeight.class));
    }

    @Test
    void testTuneWeightsSuccess() {
        // Arrange
        ConfidenceWeight w1 = new ConfidenceWeight();
        w1.setFactor("Trend");
        w1.setWeight(20.0);
        w1.setActive(true);

        when(confidenceWeightRepository.findByActiveTrue()).thenReturn(List.of(w1));

        TradeSignal signal = new TradeSignal();
        signal.setId(1L);

        TradeResult result = new TradeResult();
        result.setSignal(signal);
        result.setOutcome("TARGET2"); // Win!
        result.setProfitLoss(30.0);

        when(tradeResultRepository.findAll()).thenReturn(List.of(result));

        SignalExplanation exp = new SignalExplanation();
        exp.setSignal(signal);
        exp.setFactor("Trend");
        exp.setScore(80.0); // positive score

        when(signalExplanationRepository.findBySignalIdIn(anyList())).thenReturn(List.of(exp));

        // Act
        adaptiveWeightsService.tuneWeights();

        // Assert
        verify(confidenceWeightRepository, atLeastOnce()).save(w1);
    }
}
