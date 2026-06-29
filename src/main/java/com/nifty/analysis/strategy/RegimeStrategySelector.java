package com.nifty.analysis.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P5-1: routes the market regime + chosen direction to a {@link StrategyType}.
 *
 * <p>Config-gated by {@code nifty.strategy.spreads-enabled} (default FALSE): when off, it always
 * returns the existing single-leg long (LONG_CALL / LONG_PUT), so behaviour is unchanged until the
 * multi-leg lifecycle (execution + resolution) is wired and you opt in.
 *
 * <p>When on:
 * <ul>
 *   <li>TRENDING_BULLISH/BEARISH → LONG_CALL / LONG_PUT (directional conviction, ride the move)</li>
 *   <li>SIDEWAYS or HIGH_VOLATILITY → IRON_CONDOR (sell range premium with capped risk)</li>
 *   <li>otherwise (mild trend / NEUTRAL with a direction) → BULL_CALL_SPREAD / BEAR_PUT_SPREAD
 *       (defined-risk directional; cheaper theta than a naked long)</li>
 * </ul>
 */
@Component
@Slf4j
public class RegimeStrategySelector {

    @Value("${nifty.strategy.spreads-enabled:false}")
    private boolean spreadsEnabled;

    /**
     * @param regimeBias       MarketRegimeAgent bias ("TRENDING_BULLISH", "SIDEWAYS", "HIGH_VOLATILITY", ...)
     * @param directionBullish chosen trade direction (ignored for non-directional iron condor)
     */
    public StrategyType select(String regimeBias, boolean directionBullish) {
        return select(regimeBias, directionBullish, spreadsEnabled);
    }

    /** Pure routing logic (the {@code enabled} flag is passed in so it's unit-testable). */
    static StrategyType select(String regimeBias, boolean directionBullish, boolean spreadsEnabled) {
        if (!spreadsEnabled) {
            return directionBullish ? StrategyType.LONG_CALL : StrategyType.LONG_PUT;
        }
        String r = regimeBias == null ? "" : regimeBias.toUpperCase();
        if (r.contains("SIDEWAYS") || r.contains("VOLATIL")) {
            return StrategyType.IRON_CONDOR; // range / high-IV → sell premium, capped risk
        }
        if ("TRENDING_BULLISH".equals(r)) {
            return StrategyType.LONG_CALL;
        }
        if ("TRENDING_BEARISH".equals(r)) {
            return StrategyType.LONG_PUT;
        }
        // Mild trend / NEUTRAL-with-direction → defined-risk directional spread.
        return directionBullish ? StrategyType.BULL_CALL_SPREAD : StrategyType.BEAR_PUT_SPREAD;
    }
}
