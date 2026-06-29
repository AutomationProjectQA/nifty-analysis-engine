package com.nifty.analysis.strategy;

/**
 * P5-1: pure DEFINED-RISK payoff math for vertical spreads and iron condors. Given per-unit leg
 * premiums and the strike width, it computes the capped max profit / max loss (in INR for the given
 * quantity) and the break-evens — so the engine and risk gate always know the worst case up front.
 * No naked/unlimited exposure. Pure + unit-tested.
 */
public final class SpreadPayoff {

    private SpreadPayoff() {}

    /** A vertical spread outcome (INR, for the supplied quantity). {@code credit} true = net credit received. */
    public record Vertical(boolean credit, double netPremiumInr, double maxProfitInr, double maxLossInr) {}

    /** An iron condor outcome (INR), plus the underlying break-even band. */
    public record Condor(double netCreditInr, double maxProfitInr, double maxLossInr,
                         double lowerBreakeven, double upperBreakeven) {}

    /**
     * Vertical spread. {@code net = shortPremium - longPremium}: >= 0 is a credit spread,
     * &lt; 0 a debit spread. Risk is capped by the strike width either way.
     *
     * @param strikeWidth difference between the two strikes (points)
     * @param quantity    total units (lots × lot size)
     */
    public static Vertical vertical(double longPremium, double shortPremium, double strikeWidth, int quantity) {
        double netPerUnit = shortPremium - longPremium;
        boolean credit = netPerUnit >= 0;
        double maxProfitPerUnit, maxLossPerUnit;
        if (credit) {
            maxProfitPerUnit = netPerUnit;                 // keep the credit if it expires worthless
            maxLossPerUnit = strikeWidth - netPerUnit;     // capped at the width minus credit
        } else {
            double debit = -netPerUnit;
            maxProfitPerUnit = strikeWidth - debit;        // width minus what you paid
            maxLossPerUnit = debit;                        // can only lose the debit
        }
        return new Vertical(credit,
                round2(netPerUnit * quantity),
                round2(Math.max(0.0, maxProfitPerUnit) * quantity),
                round2(Math.max(0.0, maxLossPerUnit) * quantity));
    }

    /**
     * Iron condor = short OTM call spread + short OTM put spread (both credit), equal wing width.
     *
     * @param netCreditPerUnit total premium received per unit (both spreads)
     * @param wingWidth        strike width of each wing (points)
     */
    public static Condor ironCondor(double shortPutStrike, double shortCallStrike,
                                    double wingWidth, double netCreditPerUnit, int quantity) {
        double maxProfit = netCreditPerUnit * quantity;
        double maxLoss = Math.max(0.0, wingWidth - netCreditPerUnit) * quantity; // only one side can be breached
        double lowerBreakeven = shortPutStrike - netCreditPerUnit;
        double upperBreakeven = shortCallStrike + netCreditPerUnit;
        return new Condor(round2(maxProfit), round2(maxProfit), round2(maxLoss),
                round2(lowerBreakeven), round2(upperBreakeven));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
