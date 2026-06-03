package com.nifty.analysis.service;

import com.nifty.analysis.entity.ConfidenceWeight;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptiveWeightsService {

    private final TradeResultRepository tradeResultRepository;
    private final SignalExplanationRepository signalExplanationRepository;
    private final ConfidenceWeightRepository confidenceWeightRepository;

    private static final double LEARNING_RATE = 0.05; // Weight adjustment step size

    /**
     * Scheduled job that automatically tunes confidence weights weekly.
     * Can also be triggered manually.
     */
    @Scheduled(cron = "0 0 0 * * SUN") // Run every Sunday at midnight
    @Transactional
    public void tuneWeights() {
        log.info("Starting confidence weights optimization cycle...");

        List<TradeResult> results = tradeResultRepository.findAll();
        if (results.isEmpty()) {
            log.info("No historical trade results available. Skipping optimization.");
            return;
        }

        // Fetch all explanations in a single query to prevent N+1 query overhead
        List<Long> signalIds = results.stream()
                .map(r -> r.getSignal().getId())
                .toList();
        List<SignalExplanation> allExplanations = signalExplanationRepository.findBySignalIdIn(signalIds);
        Map<Long, List<SignalExplanation>> explanationsMap = allExplanations.stream()
                .collect(java.util.stream.Collectors.groupingBy(exp -> exp.getSignal().getId()));

        List<ConfidenceWeight> activeWeights = confidenceWeightRepository.findByActiveTrue();
        Map<String, Double> weightsMap = new HashMap<>();
        for (ConfidenceWeight cw : activeWeights) {
            weightsMap.put(cw.getFactor(), cw.getWeight());
        }

        // Cumulative weight adjustments
        Map<String, Double> adjustments = new HashMap<>();
        for (String factor : weightsMap.keySet()) {
            adjustments.put(factor, 0.0);
        }

        for (TradeResult result : results) {
            String outcome = result.getOutcome();
            Long signalId = result.getSignal().getId();
            List<SignalExplanation> explanations = explanationsMap.getOrDefault(signalId, java.util.Collections.emptyList());

            boolean isWin = "TARGET1".equals(outcome) || "TARGET2".equals(outcome);
            boolean isLoss = "STOP_LOSS".equals(outcome);

            if (!isWin && !isLoss) {
                continue; // Skip expired or active trades
            }

            for (SignalExplanation exp : explanations) {
                String factor = exp.getFactor();
                if (!weightsMap.containsKey(factor)) {
                    continue; // Skip indicators that are not dynamic weights
                }

                // If factor score was positive (>50), and trade won: strengthen weight
                // If factor score was positive (>50), but trade lost: weaken weight
                double normalizedScore = (exp.getScore() - 50.0) / 50.0; // scale from -1.0 to +1.0
                
                double delta = isWin ? (normalizedScore * LEARNING_RATE) : (-normalizedScore * LEARNING_RATE);
                adjustments.put(factor, adjustments.get(factor) + delta);
            }
        }

        // Apply adjustments and normalize weights so they sum to 100.0
        double totalNewWeight = 0.0;
        Map<String, Double> newWeights = new HashMap<>();

        for (Map.Entry<String, Double> entry : weightsMap.entrySet()) {
            String factor = entry.getKey();
            double currentWeight = entry.getValue();
            double adj = adjustments.getOrDefault(factor, 0.0);
            
            // Ensure weights do not drop below a baseline minimum (e.g. 5.0%)
            double newWeight = Math.max(5.0, currentWeight + adj);
            newWeights.put(factor, newWeight);
            totalNewWeight += newWeight;
        }

        // Normalize back to 100% total
        for (ConfidenceWeight cw : activeWeights) {
            double rawNewWeight = newWeights.get(cw.getFactor());
            double normalizedWeight = Math.round((rawNewWeight / totalNewWeight) * 100.0 * 100.0) / 100.0;
            cw.setWeight(normalizedWeight);
            confidenceWeightRepository.save(cw);
            log.info("Optimized Weight for factor '{}': {}% (Adjustment = {})", 
                    cw.getFactor(), cw.getWeight(), adjustments.get(cw.getFactor()));
        }

        log.info("Confidence weights optimization cycle complete.");
    }
}
