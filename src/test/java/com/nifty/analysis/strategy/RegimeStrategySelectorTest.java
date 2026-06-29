package com.nifty.analysis.strategy;

import org.junit.jupiter.api.Test;

import static com.nifty.analysis.strategy.RegimeStrategySelector.select;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegimeStrategySelectorTest {

    @Test
    void disabled_alwaysSingleLegLong() {
        assertEquals(StrategyType.LONG_CALL, select("SIDEWAYS", true, false));
        assertEquals(StrategyType.LONG_PUT, select("TRENDING_BEARISH", false, false));
    }

    @Test
    void enabled_rangeOrHighVol_isIronCondor() {
        assertEquals(StrategyType.IRON_CONDOR, select("SIDEWAYS", true, true));
        assertEquals(StrategyType.IRON_CONDOR, select("HIGH_VOLATILITY", false, true));
    }

    @Test
    void enabled_strongTrend_isDirectionalLong() {
        assertEquals(StrategyType.LONG_CALL, select("TRENDING_BULLISH", true, true));
        assertEquals(StrategyType.LONG_PUT, select("TRENDING_BEARISH", false, true));
    }

    @Test
    void enabled_mildOrNeutral_isDefinedRiskSpread() {
        assertEquals(StrategyType.BULL_CALL_SPREAD, select("NEUTRAL", true, true));
        assertEquals(StrategyType.BEAR_PUT_SPREAD, select("NEUTRAL", false, true));
    }
}
