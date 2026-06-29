package com.nifty.analysis.strategy;

import java.util.List;
import java.util.function.BiFunction;

/**
 * P5-1: constructs the actual legs of a defined-risk strategy around the ATM strike, prices them
 * via a premium lookup, and computes the capped payoff. Pure (premium source injected) + unit-tested.
 */
public final class StrategyBuilder {

    private StrategyBuilder() {}

    /** One constructed leg with its priced premium (per unit). */
    public record Leg(String action, String optionType, int strike, double premium) {}

    /**
     * A built strategy ready to persist: its legs, the NET cash per unit (positive = credit received,
     * negative = debit paid), and the capped max profit / max loss in INR.
     */
    public record Built(StrategyType type, List<Leg> legs, double netPremiumPerUnit,
                        double maxProfitInr, double maxLossInr) {}

    /**
     * @param type        the multi-leg strategy (BULL_CALL_SPREAD / BEAR_PUT_SPREAD / IRON_CONDOR)
     * @param atmStrike   at-the-money strike
     * @param step        instrument strike step (NIFTY 50, BANKNIFTY 100)
     * @param widthSteps  spread width in steps (e.g. 2 → 2×step points wide)
     * @param premium     (optionType "CE"/"PE", strike) → premium per unit
     * @param quantity    units (lots × lot size)
     */
    public static Built build(StrategyType type, int atmStrike, int step, int widthSteps,
                              BiFunction<String, Integer, Double> premium, int quantity) {
        int w = step * Math.max(1, widthSteps);
        return switch (type) {
            case BULL_CALL_SPREAD -> {
                int lo = atmStrike, hi = atmStrike + w;
                double buy = premium.apply("CE", lo), sell = premium.apply("CE", hi);
                SpreadPayoff.Vertical p = SpreadPayoff.vertical(buy, sell, w, quantity);
                yield new Built(type, List.of(
                        new Leg("BUY", "CE", lo, buy), new Leg("SELL", "CE", hi, sell)),
                        round2(sell - buy), p.maxProfitInr(), p.maxLossInr());
            }
            case BEAR_PUT_SPREAD -> {
                int hi = atmStrike, lo = atmStrike - w;
                double buy = premium.apply("PE", hi), sell = premium.apply("PE", lo);
                SpreadPayoff.Vertical p = SpreadPayoff.vertical(buy, sell, w, quantity);
                yield new Built(type, List.of(
                        new Leg("BUY", "PE", hi, buy), new Leg("SELL", "PE", lo, sell)),
                        round2(sell - buy), p.maxProfitInr(), p.maxLossInr());
            }
            case IRON_CONDOR -> {
                int shortCall = atmStrike + w, longCall = atmStrike + 2 * w;
                int shortPut = atmStrike - w, longPut = atmStrike - 2 * w;
                double sc = premium.apply("CE", shortCall), lc = premium.apply("CE", longCall);
                double sp = premium.apply("PE", shortPut), lp = premium.apply("PE", longPut);
                double netCredit = (sc - lc) + (sp - lp); // credit from both wings
                SpreadPayoff.Condor p = SpreadPayoff.ironCondor(shortPut, shortCall, w, netCredit, quantity);
                yield new Built(type, List.of(
                        new Leg("SELL", "CE", shortCall, sc), new Leg("BUY", "CE", longCall, lc),
                        new Leg("SELL", "PE", shortPut, sp), new Leg("BUY", "PE", longPut, lp)),
                        round2(netCredit), p.maxProfitInr(), p.maxLossInr());
            }
            default -> throw new IllegalArgumentException("Not a multi-leg strategy: " + type);
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
