package com.nifty.analysis.scheduler;

import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketCandleRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.service.MarketCollectorService;
import com.nifty.analysis.service.MarketTickCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * P5-3: catches intrabar moves between the 1-minute cycles. Watches the streamed spot
 * ({@link MarketTickCache}) for a VWAP cross or a break of the previous 15m candle's high/low and,
 * on a fresh edge (with a cooldown to prevent spam), fires a full evaluation on demand via
 * {@link MarketCollectorService#tryCollect()} — which shares the same non-reentrancy guard as the
 * scheduled cycle, so the two can never overlap. The 1-minute scheduler remains the backstop.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntradayEventTrigger {

    private final MarketTickCache tickCache;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final MarketCollectorService marketCollectorService;

    @Value("${nifty.trigger.enabled:true}")
    private boolean enabled;
    @Value("${nifty.trigger.cooldown-seconds:30}")
    private long cooldownSeconds;

    private volatile Double lastSpot;       // previous observed spot, for edge detection
    private volatile long lastTriggerMs = 0;

    @Scheduled(fixedDelayString = "${nifty.trigger.check-interval-ms:5000}")
    public void check() {
        if (!enabled || !tickCache.hasData()) {
            return;
        }
        double spot = tickCache.getNiftySpot();
        if (spot <= 0) {
            return;
        }

        Double vwap = marketSnapshotRepository.findLatest().map(MarketSnapshot::getVwap).orElse(null);
        Double refHigh = null, refLow = null;
        List<MarketCandle> candles = marketCandleRepository.findLatestByTimeframe("15m", 2);
        if (!candles.isEmpty()) {
            // Use the PREVIOUS completed 15m candle (index 1) as the breakout reference, not the
            // still-forming one (index 0) whose high/low trivially equals the running extreme.
            MarketCandle ref = candles.size() >= 2 ? candles.get(1) : candles.get(0);
            refHigh = ref.getHigh();
            refLow = ref.getLow();
        }

        Double prev = lastSpot;
        lastSpot = spot;
        Optional<String> event = detectEvent(prev, spot, vwap, refHigh, refLow);
        if (event.isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTriggerMs < cooldownSeconds * 1000L) {
            log.debug("Intraday event {} within {}s cooldown — not triggering.", event.get(), cooldownSeconds);
            return;
        }
        lastTriggerMs = nowMs;
        log.info("Intraday event {} at spot {} → firing on-demand evaluation.", event.get(), spot);
        marketCollectorService.tryCollect();
    }

    /**
     * Pure intrabar-event detector (edge-triggered): returns the event type only when the spot
     * just CROSSED a level versus the previous observation (so it fires once per crossing, not
     * every tick while beyond it). Null levels are skipped.
     */
    static Optional<String> detectEvent(Double prevSpot, double spot, Double vwap, Double refHigh, Double refLow) {
        if (prevSpot == null) {
            return Optional.empty(); // need a baseline to detect an edge
        }
        if (vwap != null) {
            if (prevSpot <= vwap && spot > vwap) return Optional.of("VWAP_CROSS_UP");
            if (prevSpot >= vwap && spot < vwap) return Optional.of("VWAP_CROSS_DOWN");
        }
        if (refHigh != null && prevSpot <= refHigh && spot > refHigh) {
            return Optional.of("HTF_BREAKOUT_UP");
        }
        if (refLow != null && prevSpot >= refLow && spot < refLow) {
            return Optional.of("HTF_BREAKDOWN");
        }
        return Optional.empty();
    }
}
