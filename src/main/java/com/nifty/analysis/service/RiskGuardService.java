package com.nifty.analysis.service;

import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Central risk gate. Enforces the daily trading limits configured under
 * {@code nifty.risk} before any new trade is opened. Existing/open trades are
 * unaffected — this only governs opening NEW positions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskGuardService {

    // Global kill switch. false => no new trades are opened at all.
    @Value("${nifty.risk.trading-enabled:true}")
    private boolean tradingEnabled;

    // Maximum number of new trades (signals) allowed per trading day.
    @Value("${nifty.risk.max-trades-per-day:5}")
    private int maxTradesPerDay;

    // Stop opening new trades once cumulative realised loss for the day reaches
    // this magnitude. Unit matches recorded trade P&L (TradeResult.profitLoss).
    @Value("${nifty.risk.max-loss-per-day:1000.0}")
    private double maxLossPerDay;

    // Data provider in use. The simulated-data block only applies to the live "angelone"
    // provider; pure "simulated" mode is an intentional demo and is never blocked.
    @Value("${nifty.collector.provider:angelone}")
    private String provider;

    // Refuse to open new trades when the live feed has degraded to simulated data.
    // Kill-switch: set false to disable this guard entirely.
    @Value("${nifty.risk.block-on-simulated-data:true}")
    private boolean blockOnSimulatedData;

    private final TradeSignalRepository tradeSignalRepository;
    private final TradeResultRepository tradeResultRepository;
    private final DataFeedStatus dataFeedStatus;

    /**
     * Checks whether a new trade may be opened right now under the configured
     * daily risk limits.
     */
    public RiskCheck canOpenNewTrade() {
        if (!tradingEnabled) {
            return RiskCheck.deny("Trading is disabled (kill switch nifty.risk.trading-enabled=false).");
        }

        // Never trade on simulated/degraded data in live (angelone) mode. This only
        // blocks when the feed has actually fallen back to simulation — when live data
        // is flowing, dataFeedStatus.isLive() is true and trading proceeds normally.
        if (blockOnSimulatedData && "angelone".equalsIgnoreCase(provider) && !dataFeedStatus.isLive()) {
            return RiskCheck.deny("Market data is simulated/degraded (live broker feed unavailable). "
                    + "Refusing to open new trades on fabricated prices.");
        }

        LocalDateTime startOfDay = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .toLocalDate().atStartOfDay();
        List<TradeSignal> todaySignals = tradeSignalRepository.findBySignalTimeAfter(startOfDay);

        int tradeCount = todaySignals.size();
        if (tradeCount >= maxTradesPerDay) {
            return RiskCheck.deny(String.format(
                    "Max trades per day reached (%d/%d).", tradeCount, maxTradesPerDay));
        }

        double realisedPnl = tradeResultRepository.sumProfitLossSince(startOfDay);

        if (realisedPnl <= -Math.abs(maxLossPerDay)) {
            return RiskCheck.deny(String.format(
                    "Daily loss limit hit (realised P&L %.2f <= -%.2f).", realisedPnl, Math.abs(maxLossPerDay)));
        }

        return RiskCheck.allow();
    }

    /** Result of a risk check: whether a trade is allowed and, if not, why. */
    public record RiskCheck(boolean allowed, String reason) {
        public static RiskCheck allow() {
            return new RiskCheck(true, null);
        }

        public static RiskCheck deny(String reason) {
            return new RiskCheck(false, reason);
        }
    }
}
