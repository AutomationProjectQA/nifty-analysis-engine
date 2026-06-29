package com.nifty.analysis.service;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for "is the data REAL and flowing?" — aggregates every data source into one
 * verdict so a critical problem (simulated feed, stale data, insane spot, blank Gemini key, no trades)
 * is caught automatically instead of by manual page-checking. Exposed at /api/v1/health/data and
 * polled by a scheduled alerter that Telegrams on DEGRADED. This operationalises the "fail loud, never
 * hide a fallback" rule that the production bugs violated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataHealthService {

    private static final String INSTRUMENT = "NIFTY";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DataFeedStatus dataFeedStatus;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final TradeSignalRepository tradeSignalRepository;
    private final TelegramBotService telegramBotService;

    @Value("${nifty.collector.provider:angelone}")
    private String provider;
    @Value("${nifty.ai.gemini-api-key:}")
    private String geminiApiKey;
    @Value("${nifty.health.spot-min:15000}")
    private double spotMin;
    @Value("${nifty.health.spot-max:50000}")
    private double spotMax;
    @Value("${nifty.health.max-stale-seconds:300}")
    private long maxStaleSeconds;
    @Value("${nifty.health.alert-enabled:true}")
    private boolean alertEnabled;

    // Throttle: only re-alert when the problem set CHANGES (incl. recovery), not every poll.
    private volatile String lastAlertSignature = "";

    public record Report(String status, List<String> problems, Map<String, Object> details) {}

    /** Builds the health verdict. Staleness / no-trades are only flagged during market hours. */
    public Report report() {
        List<String> problems = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        ZonedDateTime nowIst = ZonedDateTime.now(IST);
        boolean marketHours = isMarketHours(nowIst);
        details.put("marketHours", marketHours);
        details.put("provider", provider);

        // 1. Feed live vs simulated (the dominant failure mode).
        boolean live = "angelone".equalsIgnoreCase(provider) && dataFeedStatus.isLive(INSTRUMENT);
        details.put("feed", live ? "LIVE" : "SIMULATED");
        if ("angelone".equalsIgnoreCase(provider) && !live) {
            problems.add("NIFTY feed is SIMULATED/not-live (no live trades; values may be fake)");
        }

        // 2. Latest NIFTY snapshot: presence, freshness, spot sanity.
        MarketSnapshot snap = marketSnapshotRepository.findLatestByInstrument(INSTRUMENT).orElse(null);
        if (snap == null) {
            problems.add("No NIFTY market snapshot persisted");
        } else {
            double spot = snap.getNiftySpot() != null ? snap.getNiftySpot() : 0.0;
            details.put("niftySpot", spot);
            details.put("snapshotTime", snap.getSnapshotTime() != null ? snap.getSnapshotTime().toString() : null);
            if (spot < spotMin || spot > spotMax) {
                problems.add("NIFTY spot " + spot + " outside sane band [" + spotMin + "," + spotMax + "] (likely simulated/misparsed)");
            }
            if (marketHours && snap.getSnapshotTime() != null) {
                long ageSec = Duration.between(snap.getSnapshotTime(), nowIst.toLocalDateTime()).getSeconds();
                details.put("snapshotAgeSeconds", ageSec);
                if (ageSec > maxStaleSeconds) {
                    problems.add("Market snapshot stale: " + ageSec + "s old (> " + maxStaleSeconds + "s)");
                }
            }
        }

        // 3. Latest option chain freshness (market hours).
        LocalDateTime optTime = optionSnapshotRepository.findLatestSnapshotTimeByInstrument(INSTRUMENT);
        details.put("optionChainTime", optTime != null ? optTime.toString() : null);
        if (optTime == null) {
            problems.add("No NIFTY option chain persisted");
        } else if (marketHours) {
            long ageSec = Duration.between(optTime, nowIst.toLocalDateTime()).getSeconds();
            if (ageSec > maxStaleSeconds) {
                problems.add("Option chain stale: " + ageSec + "s old (> " + maxStaleSeconds + "s)");
            }
        }

        // 4. Gemini key (reports/news silently degrade without it).
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            problems.add("GEMINI_API_KEY blank (AI reports/news will use static templates)");
        }

        // 5. No-trade watch: zero signals so far today during market hours (informational-critical).
        long signalsToday = tradeSignalRepository.findAllByOrderBySignalTimeDesc().stream()
                .filter(s -> s.getSignalTime() != null && s.getSignalTime().toLocalDate().equals(LocalDate.now(IST)))
                .count();
        details.put("signalsToday", signalsToday);
        if (marketHours && signalsToday == 0 && nowIst.toLocalTime().isAfter(LocalTime.of(10, 30))) {
            problems.add("No signals generated today (after 10:30) — check /api/v1/signals/decision-funnel");
        }

        String status = problems.isEmpty() ? "HEALTHY" : "DEGRADED";
        details.put("checkedAtIst", nowIst.toLocalDateTime().toString());
        return new Report(status, problems, details);
    }

    /** Polls health during market hours and Telegrams when the problem set changes. */
    @Scheduled(fixedDelayString = "${nifty.health.alert-interval-ms:300000}")
    public void checkAndAlert() {
        if (!alertEnabled) return;
        ZonedDateTime nowIst = ZonedDateTime.now(IST);
        if (!isMarketHours(nowIst)) return; // off-hours degradation is expected; don't page

        Report r = report();
        String signature = r.status() + "|" + String.join(";", r.problems());
        if (signature.equals(lastAlertSignature)) return; // no change → no repeat alert
        lastAlertSignature = signature;

        if ("DEGRADED".equals(r.status())) {
            String msg = "⚠️ *Data health DEGRADED*\n- " + String.join("\n- ", r.problems());
            try { telegramBotService.sendMessage(msg); } catch (Exception e) { log.warn("Health alert send failed: {}", e.getMessage()); }
            log.warn("Data health DEGRADED: {}", r.problems());
        } else {
            try { telegramBotService.sendMessage("✅ Data health recovered — all checks passing."); } catch (Exception ignored) { }
            log.info("Data health recovered to HEALTHY.");
        }
    }

    private static boolean isMarketHours(ZonedDateTime ist) {
        var day = ist.getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) return false;
        LocalTime t = ist.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }
}
