package com.nifty.analysis.service;

import org.springframework.stereotype.Service;

/**
 * Black-Scholes European option pricing (no dividend) used to derive theoretical
 * CE/PE premiums for the strategy payoff builder from the option chain's IV.
 */
@Service
public class BlackScholesService {

    private static final double RISK_FREE_RATE = 0.065; // ~6.5% India risk-free

    /** Standard normal cumulative distribution via the Abramowitz-Stegun approximation. */
    private static double normCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422804014327 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? 1.0 - p : p;
    }

    /**
     * @param spot       underlying spot
     * @param strike     option strike
     * @param ivPercent  implied volatility as a percentage (e.g. 12.5 for 12.5%)
     * @param years      time to expiry in years (floored internally to avoid T=0)
     * @param isCall     true for CE, false for PE
     * @return theoretical premium (>= 0)
     */
    public double price(double spot, double strike, double ivPercent, double years, boolean isCall) {
        double sigma = Math.max(ivPercent, 0.01) / 100.0;
        double t = Math.max(years, 1.0 / 365.0); // floor at ~1 day
        if (spot <= 0 || strike <= 0) {
            return 0.0;
        }

        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(spot / strike) + (RISK_FREE_RATE + sigma * sigma / 2.0) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double discountedK = strike * Math.exp(-RISK_FREE_RATE * t);

        double premium = isCall
                ? spot * normCdf(d1) - discountedK * normCdf(d2)
                : discountedK * normCdf(-d2) - spot * normCdf(-d1);

        return Math.max(0.0, Math.round(premium * 100.0) / 100.0);
    }
}
