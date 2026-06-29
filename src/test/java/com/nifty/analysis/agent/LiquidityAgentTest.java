package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.OptionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-1 AG-F4: liquidity scoring is continuous in OI, config-driven, and null-OI-safe. */
class LiquidityAgentTest {

    private LiquidityAgent agent;

    @BeforeEach
    void setUp() {
        agent = new LiquidityAgent();
        ReflectionTestUtils.setField(agent, "goodOiContracts", 50000L);
        ReflectionTestUtils.setField(agent, "floorFraction", 0.2);
        ReflectionTestUtils.setField(agent, "unknownOiScore", 65.0);
    }

    private OptionSnapshot ce(Long oi) {
        OptionSnapshot o = new OptionSnapshot();
        o.setStrikePrice(23500);
        o.setCeOi(oi);
        return o;
    }

    @Test
    void fullScoreAtOrAboveGoodOi() {
        assertEquals(100.0, agent.evaluateStrike(ce(50000L), true).score(), 0.01);
        assertEquals(100.0, agent.evaluateStrike(ce(120000L), true).score(), 0.01);
    }

    @Test
    void continuousBetweenFloorAndGood_notBinary() {
        // Midway between floor (10k) and good (50k) → ~50, i.e. NOT a binary 60/100.
        double mid = agent.evaluateStrike(ce(30000L), true).score();
        assertTrue(mid > 40.0 && mid < 60.0, "expected a mid continuous score, got " + mid);
        // Monotonic: more OI scores higher.
        assertTrue(agent.evaluateStrike(ce(40000L), true).score() > mid);
    }

    @Test
    void unknownOi_isNeutralAllow_notHardZero() {
        AgentResponse r = agent.evaluateStrike(ce(null), true);
        assertEquals(65.0, r.score(), 0.01);
    }
}
