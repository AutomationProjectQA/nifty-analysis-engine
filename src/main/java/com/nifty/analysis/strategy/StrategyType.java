package com.nifty.analysis.strategy;

/**
 * P5-1: the option strategy chosen for a signal, routed by market regime. Single-leg longs are the
 * existing behaviour; the multi-leg, DEFINED-RISK strategies (capped max loss) are added for
 * moderate-trend and range/high-IV regimes where buying naked options bleeds theta.
 */
public enum StrategyType {

    /** Trending bullish — buy a call (existing BUY_CE). */
    LONG_CALL(false, true),
    /** Trending bearish — buy a put (existing BUY_PE). */
    LONG_PUT(false, false),
    /** Moderately bullish, defined risk — long lower-strike call + short higher-strike call (net debit). */
    BULL_CALL_SPREAD(true, true),
    /** Moderately bearish, defined risk — long higher-strike put + short lower-strike put (net debit). */
    BEAR_PUT_SPREAD(true, false),
    /** Range / high-IV, non-directional, defined risk — short OTM call spread + short OTM put spread (net credit). */
    IRON_CONDOR(true, null);

    private final boolean multiLeg;
    private final Boolean bullish; // true=bullish, false=bearish, null=non-directional

    StrategyType(boolean multiLeg, Boolean bullish) {
        this.multiLeg = multiLeg;
        this.bullish = bullish;
    }

    public boolean isMultiLeg() {
        return multiLeg;
    }

    /** true=bullish, false=bearish, null=non-directional (iron condor). */
    public Boolean bullish() {
        return bullish;
    }
}
