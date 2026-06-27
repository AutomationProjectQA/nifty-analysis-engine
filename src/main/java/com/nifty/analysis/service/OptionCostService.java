package com.nifty.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Models the real round-trip transaction costs of a Nifty options trade (buy + sell)
 * so reported P&L is NET, not gross. On a small (e.g. 2%) target these costs frequently
 * exceed the gross gain, so omitting them makes losers look like winners and lets the
 * daily-loss limit under-count. All rates are configurable under {@code nifty.costs.*}.
 *
 * <p>Defaults reflect typical Indian discount-broker option charges (rates can change —
 * verify against your broker's contract note):
 * brokerage flat/​order, STT on sell premium, exchange txn charge, SEBI fee, GST on
 * (brokerage+txn+SEBI), and stamp duty on the buy side.
 */
@Service
public class OptionCostService {

    @Value("${nifty.costs.brokerage-per-order:20.0}")
    private double brokeragePerOrder;

    // STT on options is charged on the SELL-side premium value.
    @Value("${nifty.costs.stt-percent-on-sell:0.001}")          // 0.10%
    private double sttPercentOnSell;

    // Exchange (NSE) transaction charge on total premium turnover (both legs).
    @Value("${nifty.costs.exchange-txn-percent:0.0003503}")     // ~0.03503%
    private double exchangeTxnPercent;

    // SEBI turnover fee on total premium turnover.
    @Value("${nifty.costs.sebi-percent:0.000001}")              // 0.0001% (₹10/crore)
    private double sebiPercent;

    // Stamp duty on the BUY-side premium value.
    @Value("${nifty.costs.stamp-percent-on-buy:0.00003}")       // 0.003%
    private double stampPercentOnBuy;

    // GST on (brokerage + exchange txn + SEBI).
    @Value("${nifty.costs.gst-percent:0.18}")                   // 18%
    private double gstPercent;

    /**
     * Total round-trip cost (INR) for buying {@code quantity} options at
     * {@code entryPremium} and selling at {@code exitPremium}.
     */
    public double roundTripCost(double entryPremium, double exitPremium, int quantity) {
        if (quantity <= 0) {
            return 0.0;
        }
        double buyTurnover = Math.max(0.0, entryPremium) * quantity;
        double sellTurnover = Math.max(0.0, exitPremium) * quantity;
        double totalTurnover = buyTurnover + sellTurnover;

        double brokerage = brokeragePerOrder * 2.0;                 // buy + sell
        double stt = sttPercentOnSell * sellTurnover;
        double exchangeTxn = exchangeTxnPercent * totalTurnover;
        double sebi = sebiPercent * totalTurnover;
        double stamp = stampPercentOnBuy * buyTurnover;
        double gst = gstPercent * (brokerage + exchangeTxn + sebi);

        double total = brokerage + stt + exchangeTxn + sebi + stamp + gst;
        return Math.round(total * 100.0) / 100.0;
    }
}
