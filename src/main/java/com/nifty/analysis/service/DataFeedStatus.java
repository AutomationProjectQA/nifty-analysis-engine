package com.nifty.analysis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, PER INSTRUMENT, whether the market data currently flowing is REAL/live (from the
 * broker) or a SIMULATED fallback. Set by the data client each fetch; read by the risk gate
 * (to refuse trading on fake data) and exposed to the portal.
 *
 * <p>Per-instrument so that, e.g., a Bank Nifty fallback to simulated data does NOT poison
 * Nifty's live status (which would otherwise block Nifty trades too). Default per instrument
 * is {@code false} (not-yet-known) until its first successful live fetch.
 */
@Component
@Slf4j
public class DataFeedStatus {

    private final Map<String, Boolean> liveByInstrument = new ConcurrentHashMap<>();
    private volatile String lastUpdatedIst;

    /** Record the outcome of the latest fetch for an instrument. Logs only on a state change. */
    public void update(String instrument, boolean isLive) {
        Boolean prev = liveByInstrument.put(instrument, isLive);
        lastUpdatedIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toLocalDateTime().toString();
        if (prev == null || prev != isLive) {
            if (isLive) {
                log.info("[{}] Market data feed is now LIVE.", instrument);
            } else {
                log.warn("[{}] Market data feed is SIMULATED/degraded — new live trades blocked.", instrument);
            }
        }
    }

    public boolean isLive(String instrument) {
        return Boolean.TRUE.equals(liveByInstrument.get(instrument));
    }

    // --- Back-compat convenience: NIFTY is the primary instrument (portal + legacy callers). ---
    public void update(boolean isLive) {
        update("NIFTY", isLive);
    }

    public boolean isLive() {
        return isLive("NIFTY");
    }

    public String getLastUpdatedIst() {
        return lastUpdatedIst;
    }
}
