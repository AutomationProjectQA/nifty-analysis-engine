package com.nifty.analysis.engine;

import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P4-1: turns the engine's "confidence points" into a real, measured probability of winning.
 *
 * <p>Fits a 1-D logistic (Platt) mapping {@code P(win) = sigmoid(a·conf + b)} from the
 * confidence the gate actually saw ({@code Final_Confidence} in {@link SignalExplanation})
 * to the realised outcome ({@link TradeResult} net P&L sign). The {@link com.nifty.analysis.agent.DecisionAgent}
 * then gates on {@code P(win) ≥ break-even + margin} instead of an arbitrary points threshold.
 *
 * <p>Until at least {@code min-samples} resolved trades exist, it stays UNTRAINED and
 * {@link #probabilityOfWin} returns -1 so the caller can fall back (no cold-start blocking).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfidenceCalibrator {

    private final TradeResultRepository tradeResultRepository;
    private final SignalExplanationRepository signalExplanationRepository;

    @Value("${nifty.calibration.enabled:true}")
    private boolean enabled;
    @Value("${nifty.calibration.min-samples:30}")
    private int minSamples;
    @Value("${nifty.risk.target-profit-percent:2.0}")
    private double targetProfitPercent;
    @Value("${nifty.risk.stop-loss-percent:40.0}")
    private double stopLossPercent;

    /** The factor row whose score equals the value the gate compares (critic-adjusted confidence). */
    private static final String CALIBRATION_FACTOR = "Final_Confidence";

    // Fitted parameters (volatile: written by the scheduled trainer, read by the decision path).
    private volatile boolean trained = false;
    private volatile double coefA = 0.0;
    private volatile double coefB = 0.0;
    private volatile int sampleCount = 0;

    public boolean isTrained() {
        return enabled && trained;
    }

    public int sampleCount() {
        return sampleCount;
    }

    /**
     * Calibrated probability of winning for a given confidence value (0..100), or -1 if not yet
     * trained / disabled — so the caller can skip the calibration gate during cold start.
     */
    public double probabilityOfWin(double confidence) {
        if (!isTrained()) {
            return -1.0;
        }
        return sigmoid(coefA * (confidence / 100.0) + coefB);
    }

    /**
     * Minimum win-rate needed just to break even, from the configured reward:risk.
     * With target {@code t}% and stop {@code s}% of premium, break-even p = s / (s + t).
     */
    public double breakEvenWinRate() {
        double denom = stopLossPercent + targetProfitPercent;
        return denom <= 0 ? 0.5 : stopLossPercent / denom;
    }

    /** Refit nightly from all resolved trades. Also safe to trigger manually. */
    @Scheduled(cron = "0 30 0 * * *")
    @Transactional(readOnly = true)
    public void train() {
        if (!enabled) {
            return;
        }
        List<double[]> samples = buildSamples();
        if (samples.size() < minSamples) {
            trained = false;
            log.info("Calibration skipped: {} samples < min {}.", samples.size(), minSamples);
            return;
        }
        double[] ab = fitLogistic(samples, 3000, 0.5);
        coefA = ab[0];
        coefB = ab[1];
        sampleCount = samples.size();
        trained = true;
        log.info("Calibration fitted on {} trades: a={}, b={}. P(win|60%)={}, break-even={}",
                sampleCount, round4(coefA), round4(coefB),
                round4(probabilityOfWin(60.0)), round4(breakEvenWinRate()));
    }

    /** Joins each resolved trade's logged Final_Confidence with its win/loss label. */
    private List<double[]> buildSamples() {
        List<TradeResult> results = tradeResultRepository.findAll();
        if (results.isEmpty()) {
            return List.of();
        }
        List<Long> signalIds = results.stream()
                .filter(r -> r.getSignal() != null)
                .map(r -> r.getSignal().getId())
                .toList();
        Map<Long, Double> confBySignal = signalExplanationRepository.findBySignalIdIn(signalIds).stream()
                .filter(e -> CALIBRATION_FACTOR.equals(e.getFactor()) && e.getScore() != null && e.getSignal() != null)
                .collect(Collectors.toMap(e -> e.getSignal().getId(), SignalExplanation::getScore, (x, y) -> x));

        List<double[]> samples = new ArrayList<>();
        for (TradeResult r : results) {
            if (r.getSignal() == null || r.getProfitLoss() == null) {
                continue;
            }
            Double conf = confBySignal.get(r.getSignal().getId());
            if (conf == null) {
                continue;
            }
            samples.add(new double[]{conf / 100.0, r.getProfitLoss() > 0 ? 1.0 : 0.0});
        }
        return samples;
    }

    /**
     * Fits {@code P(y=1) = sigmoid(a·x + b)} by batch gradient descent. Pure + unit-tested.
     * @param samples each row {@code {x, y}} with x already scaled to ~[0,1] and y in {0,1}
     */
    static double[] fitLogistic(List<double[]> samples, int iterations, double lr) {
        double a = 0.0, b = 0.0;
        int n = samples.size();
        if (n == 0) {
            return new double[]{0.0, 0.0};
        }
        for (int it = 0; it < iterations; it++) {
            double gradA = 0.0, gradB = 0.0;
            for (double[] s : samples) {
                double p = sigmoid(a * s[0] + b);
                double err = p - s[1];
                gradA += err * s[0];
                gradB += err;
            }
            a -= lr * gradA / n;
            b -= lr * gradB / n;
        }
        return new double[]{a, b};
    }

    static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
