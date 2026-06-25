package com.nifty.analysis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether the market data currently flowing is REAL/live (from the broker)
 * or a SIMULATED fallback. Set by the data client each fetch; read by the risk gate
 * (to refuse trading on fake data) and exposed to the portal (so it never labels
 * simulated data as "Live").
 *
 * <p>Default is {@code false} (not-yet-known); the first successful live fetch flips
 * it to {@code true}.
 */
@Component
@Slf4j
public class DataFeedStatus {

    private final AtomicBoolean live = new AtomicBoolean(false);
    private volatile String lastUpdatedIst;

    /** Record the outcome of the latest data fetch. Logs only on a state change. */
    public void update(boolean isLive) {
        boolean prev = live.getAndSet(isLive);
        lastUpdatedIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toLocalDateTime().toString();
        if (prev != isLive) {
            if (isLive) {
                log.info("Market data feed is now LIVE.");
            } else {
                log.warn("Market data feed is SIMULATED/degraded — new live trades will be blocked.");
            }
        }
    }

    public boolean isLive() {
        return live.get();
    }

    public String getLastUpdatedIst() {
        return lastUpdatedIst;
    }
}
