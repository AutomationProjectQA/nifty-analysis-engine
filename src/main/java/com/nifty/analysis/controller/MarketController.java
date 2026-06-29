package com.nifty.analysis.controller;

import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketCandleRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.MarketCollectorService;
import com.nifty.analysis.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class MarketController {

    private final MarketCollectorService marketCollectorService;
    private final RedisService redisService;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final com.nifty.analysis.service.OptionPremiumService optionPremiumService;
    private final com.nifty.analysis.service.DataFeedStatus dataFeedStatus;

    @org.springframework.beans.factory.annotation.Value("${nifty.collector.provider:angelone}")
    private String provider;

    /** Whether the market data currently flowing is live or a simulated fallback. */
    @GetMapping("/market/feed-status")
    public ResponseEntity<java.util.Map<String, Object>> getFeedStatus() {
        boolean live = !"angelone".equalsIgnoreCase(provider) ? false : dataFeedStatus.isLive();
        return ResponseEntity.ok(java.util.Map.of(
                "dataSource", live ? "LIVE" : "SIMULATED",
                "live", live,
                "provider", provider,
                "lastUpdatedIst", dataFeedStatus.getLastUpdatedIst() == null ? "" : dataFeedStatus.getLastUpdatedIst()
        ));
    }

    @PostMapping("/market/collect")
    public ResponseEntity<String> forceCollect() {
        log.info("REST request to manually trigger data collection");
        marketCollectorService.collect();
        return ResponseEntity.ok("Data collection triggered successfully");
    }

    @GetMapping("/market/latest")
    public ResponseEntity<MarketSnapshot> getLatestMarket(
            @RequestParam(value = "instrument", defaultValue = "NIFTY") String instrument) {
        // MUST be instrument-scoped: the global "latest" / Redis key is overwritten by whichever
        // instrument's cycle ran last (e.g. BANKNIFTY), so an unscoped read returns the wrong
        // instrument's row to the Nifty dashboard. Query the latest row for THIS instrument.
        return marketSnapshotRepository.findLatestByInstrument(instrument)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * OHLC candles for the in-app chart, oldest→newest (the order charting libs expect).
     * Self-served from our own collected candle data — no external (TradingView/NSE) symbol
     * licensing involved. {@code time} is epoch seconds of the IST wall-clock so the chart's
     * time axis reads IST directly.
     */
    @GetMapping("/market/candles")
    public ResponseEntity<List<Map<String, Object>>> getCandles(
            @RequestParam(value = "instrument", defaultValue = "NIFTY") String instrument,
            @RequestParam(value = "timeframe", defaultValue = "5m") String timeframe,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        List<MarketCandle> candles =
                marketCandleRepository.findLatestByInstrumentAndTimeframe(instrument, timeframe, capped);
        List<Map<String, Object>> out = new ArrayList<>(candles.size());
        // Repo returns newest-first; emit oldest-first for the chart.
        for (int i = candles.size() - 1; i >= 0; i--) {
            MarketCandle c = candles.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", c.getTimestamp().toEpochSecond(ZoneOffset.UTC));
            m.put("open", c.getOpen());
            m.put("high", c.getHigh());
            m.put("low", c.getLow());
            m.put("close", c.getClose());
            m.put("volume", c.getVolume());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/options/premiums")
    public ResponseEntity<com.nifty.analysis.dto.OptionPremiumDto.Response> getOptionPremiums() {
        com.nifty.analysis.dto.OptionPremiumDto.Response resp = optionPremiumService.latestPremiums();
        if (resp.premiums().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/options/latest")
    public ResponseEntity<List<OptionSnapshot>> getLatestOptions(
            @RequestParam(value = "instrument", defaultValue = "NIFTY") String instrument) {
        // Instrument-scoped (same reason as /market/latest): the global option-chain cache/query is
        // not instrument-aware and can return another instrument's chain.
        LocalDateTime latestTime = optionSnapshotRepository.findLatestSnapshotTimeByInstrument(instrument);
        if (latestTime != null) {
            return ResponseEntity.ok(optionSnapshotRepository.findByInstrumentAndSnapshotTime(instrument, latestTime));
        }
        return ResponseEntity.notFound().build();
    }
}
