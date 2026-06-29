package com.nifty.analysis.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase-3 (audit #218/#219/#222): a single typed home for the core trading-policy knobs that were
 * previously duplicated across classes — the reward:risk (target/stop, read by both
 * {@code SignalEmissionService} and {@code ConfidenceCalibrator}) and the base confidence gate.
 *
 * <p>Centralising these removes the duplication and gives one place to reason about the policy.
 * Other per-class tuning flags stay local to their owners; this holds the cross-cutting essentials.
 */
@Component
@Getter
public class TradingPolicy {

    /** Base confidence gate (%). Regime/volatility add to the EFFECTIVE gate on top of this. */
    @Value("${nifty.gating-threshold:80.0}")
    private double gatingThreshold;

    /** Take-profit as % of option premium (owner-configured; intentionally 2%). */
    @Value("${nifty.risk.target-profit-percent:2.0}")
    private double targetProfitPercent;

    /** Stop-loss as % of option premium (owner-configured). Break-even win-rate = stop/(stop+target). */
    @Value("${nifty.risk.stop-loss-percent:40.0}")
    private double stopLossPercent;
}
