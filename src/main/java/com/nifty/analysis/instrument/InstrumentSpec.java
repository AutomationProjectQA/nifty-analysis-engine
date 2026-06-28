package com.nifty.analysis.instrument;

/**
 * Per-instrument contract parameters (P5-2). De-hardcodes the NIFTY-only assumptions so the
 * pipeline can run for multiple instruments (Bank Nifty, etc.).
 *
 * @param name       exchange name as it appears in the broker scrip master (e.g. "NIFTY", "BANKNIFTY")
 * @param strikeStep option strike interval (NIFTY 50, BANKNIFTY 100)
 * @param lotSize    contract lot size (NIFTY 65, BANKNIFTY 30)
 * @param enabled    whether the collector pipeline should run for this instrument yet
 */
public record InstrumentSpec(String name, int strikeStep, int lotSize, boolean enabled) {

    /** Round a spot price to the nearest valid strike for this instrument. */
    public int atmStrike(double spot) {
        return (int) (Math.round(spot / strikeStep) * strikeStep);
    }
}
