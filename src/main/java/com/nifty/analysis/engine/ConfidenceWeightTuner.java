package com.nifty.analysis.engine;

import com.nifty.analysis.entity.ConfidenceWeight;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Online learner that nudges the ConfidenceEngine's factor weights based on how
 * resolved trades actually turned out. A factor that scored high on a winner is
 * reinforced; a factor that scored high on a loser is discounted (and vice-versa).
 *
 * <p>Update rule (perceptron-style, bounded):
 * {@code weight += learningRate * reward * (factorScore - 50) / 50}, where
 * {@code reward = +1} for a win and {@code -1} for a loss. Weights are clamped to
 * {@code [MIN_WEIGHT, MAX_WEIGHT]} so no single factor can dominate or vanish.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfidenceWeightTuner {

    private final ConfidenceWeightRepository confidenceWeightRepository;
    private final SignalExplanationRepository signalExplanationRepository;

    @Value("${nifty.confidence.adaptive-enabled:true}")
    private boolean adaptiveEnabled;

    @Value("${nifty.confidence.learning-rate:0.5}")
    private double learningRate;

    private static final double MIN_WEIGHT = 2.0;
    private static final double MAX_WEIGHT = 40.0;

    // The 8 tunable factors and their seed weights (must match ConfidenceEngine defaults).
    private static final Map<String, Double> DEFAULT_WEIGHTS = new LinkedHashMap<>();
    static {
        DEFAULT_WEIGHTS.put("Trend", 15.0);
        DEFAULT_WEIGHTS.put("MultiTimeframe", 15.0);
        DEFAULT_WEIGHTS.put("OI", 15.0);
        DEFAULT_WEIGHTS.put("PCR", 15.0);
        DEFAULT_WEIGHTS.put("VWAP", 15.0);
        DEFAULT_WEIGHTS.put("RSI", 10.0);
        DEFAULT_WEIGHTS.put("Futures", 7.0);
        DEFAULT_WEIGHTS.put("Sentiment", 8.0);
    }

    /**
     * Reinforce/penalise factor weights from a resolved trade.
     *
     * @param signal the resolved trade signal
     * @param win    true if the trade hit its target, false if it stopped out
     */
    @Transactional
    public void reinforce(TradeSignal signal, boolean win) {
        if (!adaptiveEnabled) {
            return;
        }
        ensureSeeded();

        List<ConfidenceWeight> active = confidenceWeightRepository.findByActiveTrue();
        Map<String, ConfidenceWeight> byFactor = active.stream()
                .collect(Collectors.toMap(ConfidenceWeight::getFactor, w -> w, (a, b) -> a));

        List<SignalExplanation> explanations = signalExplanationRepository.findBySignalId(signal.getId());
        double reward = win ? 1.0 : -1.0;
        boolean changed = false;

        for (SignalExplanation e : explanations) {
            ConfidenceWeight cw = byFactor.get(e.getFactor());
            if (cw == null || e.getScore() == null) {
                continue; // only the 8 known factors carry tunable weights
            }
            double signed = (e.getScore() - 50.0) / 50.0;          // -1 (bearish) .. +1 (bullish)
            double delta = learningRate * reward * signed;
            double updated = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, cw.getWeight() + delta));
            updated = Math.round(updated * 100.0) / 100.0;
            if (updated != cw.getWeight()) {
                cw.setWeight(updated);
                changed = true;
            }
        }

        if (changed) {
            confidenceWeightRepository.saveAll(new ArrayList<>(byFactor.values()));
            log.info("Adaptive confidence weights updated from {} trade (signal id={}).",
                    win ? "WINNING" : "LOSING", signal.getId());
        }
    }

    /** Seeds the default factor weights the first time, so the engine reads real DB weights. */
    private void ensureSeeded() {
        if (confidenceWeightRepository.count() > 0) {
            return;
        }
        List<ConfidenceWeight> seed = new ArrayList<>();
        for (Map.Entry<String, Double> e : DEFAULT_WEIGHTS.entrySet()) {
            ConfidenceWeight cw = new ConfidenceWeight();
            cw.setFactor(e.getKey());
            cw.setWeight(e.getValue());
            cw.setActive(true);
            seed.add(cw);
        }
        confidenceWeightRepository.saveAll(seed);
        log.info("Seeded {} default confidence factor weights.", seed.size());
    }
}
