package com.nifty.analysis.engine;

import com.nifty.analysis.agent.MarketRegimeAgent;
import com.nifty.analysis.agent.OptionsAgent;
import com.nifty.analysis.agent.SentimentAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.agent.MultiTimeframeAgent;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.ConfidenceWeight;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.ConfidenceWeightRepository;
import com.nifty.analysis.service.OptionsIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfidenceEngine {

    /** P3-2: Trend, MultiTimeframe and VWAP all measure the same trend → collapse to one group. */
    private static final Set<String> TREND_GROUP = Set.of("Trend", "MultiTimeframe", "VWAP");

    private static final double RISK_FREE_RATE = 0.065; // ~6.5% India risk-free, for fair futures basis

    @Value("${nifty.confidence.decollinearize-trend:true}")
    private boolean decollinearizeTrend;

    private final ConfidenceWeightRepository confidenceWeightRepository;

    private final MarketRegimeAgent marketRegimeAgent;
    private final TechnicalAgent technicalAgent;
    private final OptionsAgent optionsAgent;
    private final SentimentAgent sentimentAgent;
    private final OptionsIndicatorService optionsIndicatorService;
    private final MultiTimeframeAgent multiTimeframeAgent;

    public RawConfidenceResult calculateRawConfidence(MarketSnapshot latest, List<OptionSnapshotDto> optionChain,
            double spotChange) {
        AgentResponse technicalBias = technicalAgent.analyze(latest);
        boolean isCall = !"BEARISH".equals(technicalBias.bias());
        return calculateRawConfidence(latest, optionChain, spotChange, isCall);
    }

    public RawConfidenceResult calculateRawConfidence(MarketSnapshot latest, List<OptionSnapshotDto> optionChain,
            double spotChange, boolean isCall) {
        log.info("Calculating raw confidence score for market tick (Direction: {})...", isCall ? "BULLISH" : "BEARISH");

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
            weightMap.put("Trend", 15.0);
            weightMap.put("MultiTimeframe", 15.0);
            weightMap.put("OI", 15.0);
            weightMap.put("PCR", 15.0);
            weightMap.put("VWAP", 15.0);
            weightMap.put("RSI", 10.0);
            weightMap.put("Futures", 7.0);
            weightMap.put("Sentiment", 8.0);
            totalWeight = 100.0;
        }

        // 2. Fetch agent scores (direction-aware)
        double trendScoreVal = marketRegimeAgent.analyze(latest.getInstrument(), latest.getSnapshotTime()).score();
        double trendScore = isCall ? trendScoreVal : 100.0 - trendScoreVal;

        Double rsi = latest.getRsi();
        double rsiScore;
        if (isCall) {
            rsiScore = rsi != null && rsi >= 55.0 && rsi <= 68.0 ? 100.0
                    : (rsi != null && rsi >= 45.0 && rsi < 55.0 ? 50.0 : 0.0);
        } else {
            rsiScore = rsi != null && rsi <= 40.0 ? 100.0
                    : (rsi != null && rsi > 40.0 && rsi <= 55.0 ? 50.0 : 0.0);
        }

        double vwapScore;
        if (isCall) {
            vwapScore = latest.getVwap() != null && latest.getNiftySpot() > latest.getVwap() ? 100.0 : 0.0;
        } else {
            vwapScore = latest.getVwap() != null && latest.getNiftySpot() < latest.getVwap() ? 100.0 : 0.0;
        }

        AgentResponse optionsAgentResponse = optionsAgent.analyze(optionChain, latest.getNiftySpot(), spotChange);
        double overallPcr = optionsIndicatorService.calculateOverallPcr(optionChain);
        double pcrScore;
        if (isCall) {
            pcrScore = overallPcr >= 1.1 ? 100.0 : (overallPcr >= 0.8 ? 50.0 : 0.0);
        } else {
            pcrScore = overallPcr <= 0.7 ? 100.0 : (overallPcr < 1.0 ? 50.0 : 0.0);
        }

        double oiScore = isCall ? optionsAgentResponse.score() : 100.0 - optionsAgentResponse.score(); // OI build-up score

        // P3-4: score the futures basis vs. its cost-of-carry FAIR value (which grows with days to
        // expiry), not against fixed absolute points — a +30 basis means rich at 1 DTE, cheap at 7 DTE.
        double futurePremium = latest.getNiftyFuture() - latest.getNiftySpot();
        long dte = com.nifty.analysis.util.TimeUtil.daysToWeeklyExpiry(latest.getSnapshotTime().toLocalDate());
        double futuresScore = futuresBasisScore(futurePremium, latest.getNiftySpot(), dte, isCall);

        double sentimentScore = isCall ? sentimentAgent.analyze().score() : 100.0 - sentimentAgent.analyze().score();

        double mtScoreVal = multiTimeframeAgent.analyze(latest.getInstrument(), latest.getSnapshotTime()).score();
        double mtScore = isCall ? mtScoreVal : 100.0 - mtScoreVal;

        // 3. Compute weighted confidence
        Map<String, Double> factorScores = new HashMap<>();

        factorScores.put("Trend", trendScore);
        factorScores.put("MultiTimeframe", mtScore);
        factorScores.put("OI", oiScore);
        factorScores.put("PCR", pcrScore);
        factorScores.put("VWAP", vwapScore);
        factorScores.put("RSI", rsiScore);
        factorScores.put("Futures", futuresScore);
        factorScores.put("Sentiment", sentimentScore);

        double rawConfidence = blendConfidence(factorScores, weightMap, decollinearizeTrend);

        log.info("Raw weighted confidence score calculated: {}% (Direction: {})", rawConfidence, isCall ? "BULLISH" : "BEARISH");
        return new RawConfidenceResult(rawConfidence, factorScores);
    }

    /**
     * Weighted blend of direction-aware factor scores. When {@code decollinearizeTrend} is true,
     * the collinear trend factors (Trend, MultiTimeframe, VWAP) are collapsed into ONE group that
     * contributes a single factor's worth (the average of their weights) on their weighted-average
     * score — so trend is counted once, not three times. Pure + unit-tested.
     */
    /**
     * P3-4: direction-aware score for the futures basis, normalized by days-to-expiry.
     * Fair basis = spot · r · (DTE/365) (cost of carry). A premium meaningfully ABOVE fair is
     * bullish carry; a discount (below fair) is bearish. The "meaningful" band scales with fair
     * value (min 8 pts) so it isn't fooled by a large absolute basis far from expiry.
     * Pure + unit-tested.
     */
    public static double futuresBasisScore(double premium, double spot, double daysToExpiry, boolean isCall) {
        double fair = spot * RISK_FREE_RATE * Math.max(daysToExpiry, 0.5) / 365.0;
        double band = Math.max(8.0, fair * 0.5);
        if (isCall) {
            if (premium >= fair + band) return 100.0; // clearly rich → bullish
            if (premium >= fair - band) return 50.0;  // around fair → neutral
            return 0.0;                               // discount → bearish
        } else {
            if (premium <= fair - band) return 100.0; // clearly cheap/discount → bearish
            if (premium <= fair + band) return 50.0;
            return 0.0;
        }
    }

    static double blendConfidence(Map<String, Double> scores, Map<String, Double> weights, boolean decollinearizeTrend) {
        double sum = 0.0, totalWeight = 0.0;

        if (decollinearizeTrend) {
            double groupWeightSum = 0.0, groupScoreWeighted = 0.0;
            for (String k : TREND_GROUP) {
                double w = weights.getOrDefault(k, 0.0);
                groupWeightSum += w;
                groupScoreWeighted += scores.getOrDefault(k, 50.0) * w;
            }
            if (groupWeightSum > 0.0) {
                double groupScore = groupScoreWeighted / groupWeightSum;     // weighted-avg trend
                double groupWeight = groupWeightSum / TREND_GROUP.size();    // counted ONCE
                sum += groupScore * groupWeight;
                totalWeight += groupWeight;
            }
        }

        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (decollinearizeTrend && TREND_GROUP.contains(e.getKey())) {
                continue; // already folded into the trend group above
            }
            double w = weights.getOrDefault(e.getKey(), 0.0);
            sum += e.getValue() * w;
            totalWeight += w;
        }

        if (totalWeight == 0.0) {
            return 50.0;
        }
        return Math.round((sum / totalWeight) * 100.0) / 100.0;
    }

    public record RawConfidenceResult(
            double rawConfidence,
            Map<String, Double> factorScores) {
    }
}
