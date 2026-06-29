package com.nifty.analysis.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadPayoffTest {

    @Test
    void creditVertical_cappedProfitAndLoss() {
        // short 70, long 30, width 100, qty 65 → net credit 40
        SpreadPayoff.Vertical v = SpreadPayoff.vertical(30.0, 70.0, 100.0, 65);
        assertTrue(v.credit());
        assertEquals(40.0 * 65, v.netPremiumInr(), 0.01);
        assertEquals(40.0 * 65, v.maxProfitInr(), 0.01);     // keep the credit
        assertEquals((100.0 - 40.0) * 65, v.maxLossInr(), 0.01);
    }

    @Test
    void debitVertical_cappedProfitAndLoss() {
        // long 120, short 70, width 100, qty 65 → net debit 50
        SpreadPayoff.Vertical v = SpreadPayoff.vertical(120.0, 70.0, 100.0, 65);
        assertFalse(v.credit());
        assertEquals((100.0 - 50.0) * 65, v.maxProfitInr(), 0.01);
        assertEquals(50.0 * 65, v.maxLossInr(), 0.01);       // can only lose the debit
    }

    @Test
    void ironCondor_cappedRiskAndBreakevens() {
        SpreadPayoff.Condor c = SpreadPayoff.ironCondor(23400, 23600, 100.0, 50.0, 65);
        assertEquals(50.0 * 65, c.maxProfitInr(), 0.01);
        assertEquals((100.0 - 50.0) * 65, c.maxLossInr(), 0.01);
        assertEquals(23350.0, c.lowerBreakeven(), 0.01); // shortPut - credit
        assertEquals(23650.0, c.upperBreakeven(), 0.01); // shortCall + credit
    }
}
