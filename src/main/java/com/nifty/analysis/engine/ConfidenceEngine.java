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

    // Phase-1 RC-S1: use continuous (ramped) factor scores instead of coarse 0/50/100 buckets, so
    // confidence is stable and a borderline setup doesn't flip TRADE↔NO-TRADE on a 1-pt move.
    @Value("${nifty.confidence.continuous-scoring:true}")
    private boolean continuousScoring;

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
        double rsiScore = continuousScoring ? rsiScoreContinuous(rsi, isCall) : rsiScoreBucketed(rsi, isCall);

        double vwapScore = continuousScoring
                ? vwapScoreContinuous(latest.getNiftySpot(), latest.getVwap(), isCall)
                : vwapScoreBucketed(latest.getNiftySpot(), latest.getVwap(), isCall);

        AgentResponse optionsAgentResponse = optionsAgent.analyze(optionChain, latest.getNiftySpot(), spotChange);
        double overallPcr = optionsIndicatorService.calculateOverallPcr(optionChain);
        double pcrScore = continuousScoring ? pcrScoreContinuous(overallPcr, isCall) : pcrScoreBucketed(overallPcr, isCall);

        double oiScore = isCall ? optionsAgentResponse.score() : 100.0 - optionsAgentResponse.score(); // OI build-up score

        // P3-4: score the futures basis vs. its cost-of-carry FAIR value (which grows with days to
        // expiry), not against fixed absolute points — a +30 basis means rich at 1 DTE, cheap at 7 DTE.
        double futurePremium = latest.getNiftyFuture() - latest.getNiftySpot();
        long dte = com.nifty.analysis.util.TimeUtil.daysToWeeklyExpiry(latest.getSnapshotTime().toLocalDate());
        double futuresScore = continuousScoring
                ? futuresBasisScoreContinuous(futurePremium, latest.getNiftySpot(), dte, isCall)
                : futuresBasisScore(futurePremium, latest.getNiftySpot(), dte, isCall);

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

    // ---- Continuous (ramped) factor scores (Phase-1 RC-S1) ----

    private static double clamp(double v) { return Math.max(0.0, Math.min(100.0, v)); }

    /** Linear ramp: 0 at {@code lo}, 100 at {@code hi} (handles hi<lo for descending ramps). */
    private static double ramp(double x, double lo, double hi) {
        if (hi == lo) return x >= hi ? 100.0 : 0.0;
        return clamp((x - lo) / (hi - lo) * 100.0);
    }

    /**
     * RSI score, continuous and monotonic up to the overbought zone. Strong-but-not-extreme momentum
     * (RSI ~70) is NOT scored 0 anymore (Phase-1 AG-F15) — it tapers only past ~78.
     */
    static double rsiScoreContinuous(Double rsi, boolean isCall) {
        if (rsi == null) return 50.0;
        if (isCall) {
            if (rsi <= 72.0) return ramp(rsi, 40.0, 60.0);     // 40→0, 60+→100
            return clamp(100.0 - ramp(rsi, 78.0, 90.0) * 0.4); // mild overbought taper toward 60
        } else {
            if (rsi >= 28.0) return ramp(rsi, 60.0, 40.0);     // 60→0, 40-→100 (descending)
            return clamp(100.0 - ramp(rsi, 22.0, 10.0) * 0.4); // mild oversold taper toward 60
        }
    }

    /** VWAP score by signed distance as a fraction of spot (±0.3% spans the full 0..100 ramp). */
    static double vwapScoreContinuous(double spot, Double vwap, boolean isCall) {
        if (vwap == null || vwap == 0.0) return 50.0;
        double dist = (spot - vwap) / vwap; // + above VWAP (bullish), − below
        double signed = isCall ? dist : -dist;
        return clamp(50.0 + (signed / 0.003) * 50.0);
    }

    /** PCR score, continuous: bullish rises with PCR (0.7→1.1 spans 0..100); bearish mirrors. */
    static double pcrScoreContinuous(double pcr, boolean isCall) {
        return isCall ? ramp(pcr, 0.7, 1.1) : ramp(pcr, 1.1, 0.7);
    }

    /** Continuous futures-basis score: ramps across the fair±band window instead of 0/50/100. */
    static double futuresBasisScoreContinuous(double premium, double spot, double daysToExpiry, boolean isCall) {
        double fair = spot * RISK_FREE_RATE * Math.max(daysToExpiry, 0.5) / 365.0;
        double band = Math.max(8.0, fair * 0.5);
        return isCall ? ramp(premium, fair - band, fair + band)
                      : ramp(premium, fair + band, fair - band);
    }

    // ---- Legacy bucketed scores (kept for A/B via nifty.confidence.continuous-scoring=false) ----

    static double rsiScoreBucketed(Double rsi, boolean isCall) {
        if (isCall) {
            return rsi != null && rsi >= 55.0 && rsi <= 68.0 ? 100.0
                    : (rsi != null && rsi >= 45.0 && rsi < 55.0 ? 50.0 : 0.0);
        }
        return rsi != null && rsi <= 40.0 ? 100.0
                : (rsi != null && rsi > 40.0 && rsi <= 55.0 ? 50.0 : 0.0);
    }

    static double vwapScoreBucketed(double spot, Double vwap, boolean isCall) {
        if (isCall) return vwap != null && spot > vwap ? 100.0 : 0.0;
        return vwap != null && spot < vwap ? 100.0 : 0.0;
    }

    static double pcrScoreBucketed(double overallPcr, boolean isCall) {
        if (isCall) return overallPcr >= 1.1 ? 100.0 : (overallPcr >= 0.8 ? 50.0 : 0.0);
        return overallPcr <= 0.7 ? 100.0 : (overallPcr < 1.0 ? 50.0 : 0.0);
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
