package com.nifty.analysis.service;

import com.nifty.analysis.entity.ConfidenceWeight;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P4-2: BATCHED confidence-weight tuner. Replaces the per-trade online nudge (which chased the
 * noise of single trades) with a periodic update over a minimum batch of resolved trades, plus a
 * HELD-OUT validation gate: candidate weights are computed on a training split and only committed
 * if they don't degrade win/loss separation on an unseen validation split. Marginal/ambiguous
 * EXPIRED trades are excluded — only clear target-hits (wins) and stop-outs (losses) train it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptiveWeightsService {

    private final TradeResultRepository tradeResultRepository;
    private final SignalExplanationRepository signalExplanationRepository;
    private final ConfidenceWeightRepository confidenceWeightRepository;

    @Value("${nifty.confidence.adaptive-enabled:true}")
    private boolean adaptiveEnabled;
    @Value("${nifty.confidence.min-batch-trades:30}")
    private int minBatchTrades;

    private static final double LEARNING_RATE = 0.05; // weight adjustment step size
    private static final double MIN_WEIGHT = 5.0;     // floor so no factor vanishes
    private static final double TRAIN_FRACTION = 0.7; // 70% train / 30% held-out validation

    /** A resolved trade as (factor→score, win) used for tuning + validation. */
    record Sample(Map<String, Double> scores, boolean win) {}

    /** Scheduled weekly batch tune; also triggerable manually. */
    @Scheduled(cron = "0 0 0 * * SUN")
    @Transactional
    public void tuneWeights() {
        if (!adaptiveEnabled) {
            log.info("Adaptive weight tuning disabled.");
            return;
        }
        List<TradeResult> results = tradeResultRepository.findAll();
        if (results.isEmpty()) {
            log.info("No historical trade results available. Skipping optimization.");
            return;
        }

        List<ConfidenceWeight> activeWeights = confidenceWeightRepository.findByActiveTrue();
        Map<String, Double> oldWeights = new HashMap<>();
        for (ConfidenceWeight cw : activeWeights) {
            oldWeights.put(cw.getFactor(), cw.getWeight());
        }
        if (oldWeights.isEmpty()) {
            log.info("No active confidence weights to tune.");
            return;
        }

        List<Sample> samples = buildSamples(results, oldWeights.keySet());
        if (samples.size() < minBatchTrades) {
            log.info("Only {} clear win/loss trades (< min batch {}). Skipping tune to avoid chasing noise.",
                    samples.size(), minBatchTrades);
            return;
        }

        // Deterministic split: train on the earlier portion, validate on the held-out remainder.
        int split = (int) Math.round(samples.size() * TRAIN_FRACTION);
        List<Sample> train = samples.subList(0, split);
        List<Sample> validation = samples.subList(split, samples.size());

        Map<String, Double> adjustments = computeAdjustments(train, oldWeights.keySet());
        Map<String, Double> newWeights = applyAndNormalize(oldWeights, adjustments);

        // Held-out check: reject the update if it would worsen win/loss separation on unseen data.
        if (hasBothClasses(validation)) {
            double sepOld = separation(validation, oldWeights);
            double sepNew = separation(validation, newWeights);
            if (sepNew < sepOld - 1e-9) {
                log.info("Rejected weight update: held-out separation would drop {} -> {}. Keeping old weights.",
                        round2(sepOld), round2(sepNew));
                return;
            }
            log.info("Held-out separation {} -> {} (accepted).", round2(sepOld), round2(sepNew));
        } else {
            log.info("Validation split lacks both classes; committing without held-out check.");
        }

        for (ConfidenceWeight cw : activeWeights) {
            cw.setWeight(newWeights.get(cw.getFactor()));
            confidenceWeightRepository.save(cw);
            log.info("Optimized weight '{}' -> {}%", cw.getFactor(), cw.getWeight());
        }
        log.info("Confidence weights optimization cycle complete over {} trades.", samples.size());
    }

    /** Builds (scores, win) samples from clear wins/losses only (EXPIRED/ACTIVE excluded). */
    private List<Sample> buildSamples(List<TradeResult> results, java.util.Set<String> tunableFactors) {
        List<Long> signalIds = results.stream()
                .filter(r -> r.getSignal() != null)
                .map(r -> r.getSignal().getId())
                .toList();
        Map<Long, List<SignalExplanation>> bySignal = signalExplanationRepository.findBySignalIdIn(signalIds).stream()
                .filter(e -> e.getSignal() != null)
                .collect(java.util.stream.Collectors.groupingBy(e -> e.getSignal().getId()));

        List<Sample> samples = new ArrayList<>();
        for (TradeResult r : results) {
            String outcome = r.getOutcome();
            boolean win = "TARGET1".equals(outcome) || "TARGET2".equals(outcome);
            boolean loss = "STOP_LOSS".equals(outcome);
            if ((!win && !loss) || r.getSignal() == null) {
                continue; // exclude EXPIRED / ACTIVE / marginal
            }
            Map<String, Double> scores = new HashMap<>();
            for (SignalExplanation e : bySignal.getOrDefault(r.getSignal().getId(), List.of())) {
                if (e.getScore() != null && tunableFactors.contains(e.getFactor())) {
                    scores.put(e.getFactor(), e.getScore());
                }
            }
            if (!scores.isEmpty()) {
                samples.add(new Sample(scores, win));
            }
        }
        return samples;
    }

    // ---- Pure helpers (unit-tested) ----

    static Map<String, Double> computeAdjustments(List<Sample> train, java.util.Set<String> factors) {
        Map<String, Double> adj = new HashMap<>();
        for (String f : factors) {
            adj.put(f, 0.0);
        }
        for (Sample s : train) {
            for (Map.Entry<String, Double> e : s.scores().entrySet()) {
                if (!adj.containsKey(e.getKey())) {
                    continue;
                }
                double signed = (e.getValue() - 50.0) / 50.0;          // -1..+1
                double delta = (s.win() ? 1.0 : -1.0) * signed * LEARNING_RATE;
                adj.merge(e.getKey(), delta, Double::sum);
            }
        }
        return adj;
    }

    static Map<String, Double> applyAndNormalize(Map<String, Double> oldWeights, Map<String, Double> adjustments) {
        Map<String, Double> raw = new HashMap<>();
        double total = 0.0;
        for (Map.Entry<String, Double> e : oldWeights.entrySet()) {
            double w = Math.max(MIN_WEIGHT, e.getValue() + adjustments.getOrDefault(e.getKey(), 0.0));
            raw.put(e.getKey(), w);
            total += w;
        }
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            normalized.put(e.getKey(), total > 0 ? round2(e.getValue() / total * 100.0) : e.getValue());
        }
        return normalized;
    }

    static double weightedScore(Map<String, Double> scores, Map<String, Double> weights) {
        double sum = 0.0, wTotal = 0.0;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            double w = weights.getOrDefault(e.getKey(), 0.0);
            sum += e.getValue() * w;
            wTotal += w;
        }
        return wTotal > 0 ? sum / wTotal : 0.0;
    }

    /** Mean weighted score on winners minus on losers — higher means the weights separate outcomes better. */
    static double separation(List<Sample> samples, Map<String, Double> weights) {
        double winSum = 0.0, lossSum = 0.0;
        int wins = 0, losses = 0;
        for (Sample s : samples) {
            double ws = weightedScore(s.scores(), weights);
            if (s.win()) { winSum += ws; wins++; } else { lossSum += ws; losses++; }
        }
        double meanWin = wins > 0 ? winSum / wins : 0.0;
        double meanLoss = losses > 0 ? lossSum / losses : 0.0;
        return meanWin - meanLoss;
    }

    static boolean hasBothClasses(List<Sample> samples) {
        boolean anyWin = false, anyLoss = false;
        for (Sample s : samples) {
            anyWin |= s.win();
            anyLoss |= !s.win();
            if (anyWin && anyLoss) {
                return true;
            }
        }
        return false;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
