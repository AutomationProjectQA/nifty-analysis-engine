package com.nifty.analysis.engine;

import com.nifty.analysis.agent.MarketRegimeAgent;
import com.nifty.analysis.agent.OptionsAgent;
import com.nifty.analysis.agent.SentimentAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.ConfidenceWeight;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfidenceEngine {

    private final ConfidenceWeightRepository confidenceWeightRepository;

    private final MarketRegimeAgent marketRegimeAgent;
    private final TechnicalAgent technicalAgent;
    private final OptionsAgent optionsAgent;
    private final SentimentAgent sentimentAgent;

    public RawConfidenceResult calculateRawConfidence(MarketSnapshot latest, List<OptionSnapshotDto> optionChain,
            double spotChange) {
        log.info("Calculating raw confidence score for market tick...");

        // 1. Fetch dynamic weights from DB
        List<ConfidenceWeight> weights = confidenceWeightRepository.findByActiveTrue();
        Map<String, Double> weightMap = new HashMap<>();
        double totalWeight = 0.0;

        for (ConfidenceWeight cw : weights) {
            weightMap.put(cw.getFactor(), cw.getWeight());
            totalWeight += cw.getWeight();
        }

        if (totalWeight == 0.0) {
            log.warn("Total weights sum to zero. Defaulting factors to equal weight.");
            weightMap.put("Trend", 20.0);
            weightMap.put("OI", 20.0);
            weightMap.put("PCR", 15.0);
            weightMap.put("VWAP", 15.0);
            weightMap.put("RSI", 10.0);
            weightMap.put("Futures", 10.0);
            weightMap.put("Sentiment", 10.0);
            totalWeight = 100.0;
        }

        // 2. Fetch agent scores
        double trendScore = marketRegimeAgent.analyze(latest.getSnapshotTime()).score();

        double rsiScore = latest.getRsi() != null && latest.getRsi() >= 55.0 && latest.getRsi() <= 68.0 ? 100.0
                : (latest.getRsi() != null && latest.getRsi() >= 45.0 && latest.getRsi() < 55.0 ? 50.0 : 0.0);
        double vwapScore = latest.getVwap() != null && latest.getNiftySpot() > latest.getVwap() ? 100.0 : 0.0;

        AgentResponse optionsAgentResponse = optionsAgent.analyze(optionChain, latest.getNiftySpot(), spotChange);
        double overallPcr = optionChain.isEmpty() ? 0.0 : optionChain.getFirst().pcr(); // Strike PCR is stored;
                                                                                        // calculateOverallPcr is better
        double pcrScore = overallPcr >= 1.1 ? 100.0 : (overallPcr >= 0.8 ? 50.0 : 0.0);
        double oiScore = optionsAgentResponse.score(); // OI build-up score

        double futurePremium = latest.getNiftyFuture() - latest.getNiftySpot();
        double futuresScore = futurePremium > 35.0 ? 100.0 : (futurePremium > 15.0 ? 50.0 : 0.0);

        double sentimentScore = sentimentAgent.analyze().score();

        // 3. Compute weighted confidence
        double weightedSum = 0.0;
        Map<String, Double> factorScores = new HashMap<>();

        factorScores.put("Trend", trendScore);
        factorScores.put("OI", oiScore);
        factorScores.put("PCR", pcrScore);
        factorScores.put("VWAP", vwapScore);
        factorScores.put("RSI", rsiScore);
        factorScores.put("Futures", futuresScore);
        factorScores.put("Sentiment", sentimentScore);

        for (Map.Entry<String, Double> entry : factorScores.entrySet()) {
            double weight = weightMap.getOrDefault(entry.getKey(), 0.0);
            weightedSum += entry.getValue() * weight;
            log.debug("Factor score details: Factor={}, Score={}, Weight={}", entry.getKey(), entry.getValue(), weight);
        }

        double rawConfidence = weightedSum / totalWeight;
        rawConfidence = Math.round(rawConfidence * 100.0) / 100.0;

        log.info("Raw weighted confidence score calculated: {}%", rawConfidence);
        return new RawConfidenceResult(rawConfidence, factorScores);
    }

    public record RawConfidenceResult(
            double rawConfidence,
            Map<String, Double> factorScores) {
    }
}
