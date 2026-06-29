package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.OptionSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores a strike's tradability from its Open Interest. Phase-1 (AG-F4): the score is now CONTINUOUS
 * in OI (not a binary 60/100), the OI floor is config-driven, and the previous hardcoded "simulated
 * spread" pass has been removed — it always passed, so it was decorative and misleading. When OI is
 * unknown (null) we DON'T hard-fail the strike; we return a neutral, allow-with-warning score so a
 * freshly-collected chain doesn't silently kill every ladder leg.
 */
@Component
public class LiquidityAgent {

    /** OI at/above this scores full marks; the score ramps linearly from {@code floorFraction}·this up to it. */
    @Value("${nifty.liquidity.good-oi-contracts:50000}")
    private long goodOiContracts;

    /** Fraction of good-OI below which a strike scores ~0 (the ramp's lower bound). */
    @Value("${nifty.liquidity.floor-fraction:0.2}")
    private double floorFraction;

    /** Score used when OI is unknown (null) — neutral "allow with warning", not a hard 0. */
    @Value("${nifty.liquidity.unknown-oi-score:65.0}")
    private double unknownOiScore;

    public AgentResponse evaluateStrike(OptionSnapshot strikeSnapshot, boolean isCall) {
        List<String> comments = new ArrayList<>();
        Long oiBoxed = isCall ? strikeSnapshot.getCeOi() : strikeSnapshot.getPeOi();

        if (oiBoxed == null) {
            comments.add("Strike " + strikeSnapshot.getStrikePrice() + " OI unknown — allowing with neutral liquidity score.");
            return new AgentResponse(unknownOiScore, unknownOiScore >= 70.0 ? "BULLISH" : "NEUTRAL", comments);
        }

        long oi = oiBoxed;
        double lower = floorFraction * goodOiContracts;
        // Continuous ramp: 0 at/below `lower`, 100 at/above `goodOiContracts`.
        double score;
        if (oi >= goodOiContracts) {
            score = 100.0;
        } else if (oi <= lower) {
            score = Math.max(0.0, (oi / Math.max(1.0, lower)) * (floorFraction * 100.0)); // small positive below floor
        } else {
            score = (oi - lower) / (goodOiContracts - lower) * 100.0;
        }
        score = Math.max(0.0, Math.min(100.0, Math.round(score * 100.0) / 100.0));

        comments.add("Strike " + strikeSnapshot.getStrikePrice() + " OI=" + oi
                + " (good>=" + goodOiContracts + ") → liquidity " + score + "%");
        String bias = score >= 70.0 ? "BULLISH" : "NEUTRAL"; // "liquid / clear to trade"
        return new AgentResponse(score, bias, comments);
    }
}
