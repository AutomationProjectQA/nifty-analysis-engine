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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdaptiveWeightsServiceTest {

    @Mock private TradeResultRepository tradeResultRepository;
    @Mock private SignalExplanationRepository signalExplanationRepository;
    @Mock private ConfidenceWeightRepository confidenceWeightRepository;

    private AdaptiveWeightsService adaptiveWeightsService;

    @BeforeEach
    void setUp() {
        adaptiveWeightsService = new AdaptiveWeightsService(
                tradeResultRepository, signalExplanationRepository, confidenceWeightRepository);
        ReflectionTestUtils.setField(adaptiveWeightsService, "adaptiveEnabled", true);
        ReflectionTestUtils.setField(adaptiveWeightsService, "minBatchTrades", 4);
    }

    @Test
    void noHistory_skips() {
        when(tradeResultRepository.findAll()).thenReturn(Collections.emptyList());
        adaptiveWeightsService.tuneWeights();
        verify(confidenceWeightRepository, never()).save(any(ConfidenceWeight.class));
    }

    @Test
    void belowMinBatch_skipsToAvoidNoise() {
        ConfidenceWeight w = weight("Trend", 20.0);
        when(confidenceWeightRepository.findByActiveTrue()).thenReturn(List.of(w));
        // Only 2 resolved trades, below the min batch of 4 → no tuning.
        List<TradeResult> results = new ArrayList<>();
        List<SignalExplanation> exps = new ArrayList<>();
        build(results, exps, 2, true, 80.0);
        when(tradeResultRepository.findAll()).thenReturn(results);
        when(signalExplanationRepository.findBySignalIdIn(anyList())).thenReturn(exps);

        adaptiveWeightsService.tuneWeights();

        verify(confidenceWeightRepository, never()).save(any(ConfidenceWeight.class));
    }

    @Test
    void enoughBatch_commitsWeights() {
        ConfidenceWeight w = weight("Trend", 20.0);
        when(confidenceWeightRepository.findByActiveTrue()).thenReturn(List.of(w));
        List<TradeResult> results = new ArrayList<>();
        List<SignalExplanation> exps = new ArrayList<>();
        build(results, exps, 6, true, 80.0); // 6 winning trades, Trend score 80
        when(tradeResultRepository.findAll()).thenReturn(results);
        when(signalExplanationRepository.findBySignalIdIn(anyList())).thenReturn(exps);

        adaptiveWeightsService.tuneWeights();

        verify(confidenceWeightRepository, atLeastOnce()).save(w);
    }

    // ---- pure helpers ----

    @Test
    void separation_higherWhenWeightsTrackOutcomes() {
        // OI separates wins(100)/losses(0); Noise is flat 50 either way.
        AdaptiveWeightsService.Sample win = new AdaptiveWeightsService.Sample(Map.of("OI", 100.0, "Noise", 50.0), true);
        AdaptiveWeightsService.Sample loss = new AdaptiveWeightsService.Sample(Map.of("OI", 0.0, "Noise", 50.0), false);
        List<AdaptiveWeightsService.Sample> val = List.of(win, loss);

        double sepOnOi = AdaptiveWeightsService.separation(val, Map.of("OI", 90.0, "Noise", 10.0));
        double sepOnNoise = AdaptiveWeightsService.separation(val, Map.of("OI", 10.0, "Noise", 90.0));
        assertTrue(sepOnOi > sepOnNoise); // weighting the discriminating factor separates better
    }

    @Test
    void applyAndNormalize_sumsTo100() {
        Map<String, Double> out = AdaptiveWeightsService.applyAndNormalize(
                Map.of("A", 20.0, "B", 30.0), Map.of("A", 5.0, "B", -5.0));
        double total = out.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(100.0, total, 0.05);
    }

    private static ConfidenceWeight weight(String factor, double w) {
        ConfidenceWeight cw = new ConfidenceWeight();
        cw.setFactor(factor);
        cw.setWeight(w);
        cw.setActive(true);
        return cw;
    }

    /** Appends {@code n} resolved trades (win or loss) each with a single "Trend" explanation. */
    private static void build(List<TradeResult> results, List<SignalExplanation> exps, int n, boolean win, double score) {
        for (int i = 0; i < n; i++) {
            TradeSignal signal = new TradeSignal();
            signal.setId((long) (results.size() + 1));
            TradeResult r = new TradeResult();
            r.setSignal(signal);
            r.setOutcome(win ? "TARGET2" : "STOP_LOSS");
            r.setProfitLoss(win ? 30.0 : -60.0);
            results.add(r);
            SignalExplanation e = new SignalExplanation();
            e.setSignal(signal);
            e.setFactor("Trend");
            e.setScore(score);
            exps.add(e);
        }
    }
}
