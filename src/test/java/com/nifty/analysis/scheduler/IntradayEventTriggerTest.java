package com.nifty.analysis.scheduler;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntradayEventTriggerTest {

    @Test
    void vwapCross_upAndDown() {
        // prev below VWAP, now above → cross up
        assertEquals(Optional.of("VWAP_CROSS_UP"),
                IntradayEventTrigger.detectEvent(23490.0, 23510.0, 23500.0, 23600.0, 23400.0));
        // prev above, now below → cross down
        assertEquals(Optional.of("VWAP_CROSS_DOWN"),
                IntradayEventTrigger.detectEvent(23510.0, 23490.0, 23500.0, 23600.0, 23400.0));
    }

    @Test
    void htfBreakout_andBreakdown() {
        // no VWAP cross (stay above), but breaks the previous-candle high → breakout
        assertEquals(Optional.of("HTF_BREAKOUT_UP"),
                IntradayEventTrigger.detectEvent(23595.0, 23605.0, 23500.0, 23600.0, 23400.0));
        // breaks below previous-candle low → breakdown
        assertEquals(Optional.of("HTF_BREAKDOWN"),
                IntradayEventTrigger.detectEvent(23405.0, 23395.0, 23500.0, 23600.0, 23400.0));
    }

    @Test
    void noEdge_noEvent() {
        // already above VWAP and stays above, within the range → nothing
        assertTrue(IntradayEventTrigger.detectEvent(23550.0, 23560.0, 23500.0, 23600.0, 23400.0).isEmpty());
        // no baseline yet
        assertTrue(IntradayEventTrigger.detectEvent(null, 23560.0, 23500.0, 23600.0, 23400.0).isEmpty());
    }

    @Test
    void nullLevels_areSkipped() {
        assertTrue(IntradayEventTrigger.detectEvent(23490.0, 23510.0, null, null, null).isEmpty());
    }
}
