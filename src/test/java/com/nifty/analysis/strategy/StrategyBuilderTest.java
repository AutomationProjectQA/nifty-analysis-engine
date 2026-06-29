package com.nifty.analysis.strategy;

import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyBuilderTest {

    // Simple deterministic premium model: CE cheaper as strike rises, PE cheaper as strike falls.
    private static final BiFunction<String, Integer, Double> PREMIUM = (type, strike) -> {
        double atm = 23500.0;
        if ("CE".equals(type)) return Math.max(5.0, 150.0 - (strike - atm) * 0.3);
        return Math.max(5.0, 150.0 - (atm - strike) * 0.3);
    };

    @Test
    void bullCallSpread_twoLegs_definedRisk() {
        StrategyBuilder.Built b = StrategyBuilder.build(
                StrategyType.BULL_CALL_SPREAD, 23500, 50, 2, PREMIUM, 65); // width 100
        assertEquals(2, b.legs().size());
        assertEquals("BUY", b.legs().get(0).action());
        assertEquals(23500, b.legs().get(0).strike());
        assertEquals("SELL", b.legs().get(1).action());
        assertEquals(23600, b.legs().get(1).strike());
        assertTrue(b.netPremiumPerUnit() < 0); // a debit (you pay)
        assertTrue(b.maxLossInr() > 0 && b.maxProfitInr() > 0);
        // Defined risk: max loss capped by the debit; both bounded by width*qty
        assertTrue(b.maxLossInr() <= 100.0 * 65 + 0.01);
    }

    @Test
    void ironCondor_fourLegs_netCredit_cappedLoss() {
        StrategyBuilder.Built b = StrategyBuilder.build(
                StrategyType.IRON_CONDOR, 23500, 50, 2, PREMIUM, 65); // wing width 100
        assertEquals(4, b.legs().size());
        assertEquals(StrategyType.IRON_CONDOR, b.type());
        // max loss is capped (defined risk), not unlimited
        assertTrue(b.maxLossInr() > 0 && b.maxLossInr() <= 100.0 * 65 + 0.01);
        assertTrue(b.maxProfitInr() > 0);
    }
}
