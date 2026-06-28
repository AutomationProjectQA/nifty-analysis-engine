package com.nifty.analysis.instrument;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentRegistryTest {

    private final InstrumentRegistry registry = new InstrumentRegistry(false);

    @Test
    void niftySpec_strikeStep50_lot65_enabled() {
        InstrumentSpec nifty = registry.get("NIFTY");
        assertEquals(50, nifty.strikeStep());
        assertEquals(65, nifty.lotSize());
        assertTrue(nifty.enabled());
    }

    @Test
    void bankNiftySpec_strikeStep100_lot30_disabledForNow() {
        InstrumentSpec bn = registry.get("BANKNIFTY");
        assertEquals(100, bn.strikeStep());
        assertEquals(30, bn.lotSize());
        assertFalse(bn.enabled(), "Bank Nifty stays off until the data client + collector loop land");
    }

    @Test
    void active_returnsOnlyNiftyForNow() {
        List<InstrumentSpec> active = registry.active();
        assertEquals(1, active.size());
        assertEquals("NIFTY", active.get(0).name());
    }

    @Test
    void atmStrike_roundsToInstrumentStep() {
        assertEquals(23500, registry.get("NIFTY").atmStrike(23476.0));     // nearest 50
        assertEquals(51200, registry.get("BANKNIFTY").atmStrike(51234.0)); // nearest 100
    }
}
