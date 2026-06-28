package com.nifty.analysis.scheduler;

import com.nifty.analysis.service.MarketCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketScheduler {

    private final MarketCollectorService marketCollectorService;

    // Non-reentrancy guard: if a collection cycle runs longer than the schedule interval,
    // skip the next tick instead of overlapping (which could double-generate signals/orders).
    private final java.util.concurrent.atomic.AtomicBoolean collecting = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Value("${nifty.collector.market-hours-only:false}")
    private boolean marketHoursOnly;

    @Scheduled(cron = "${nifty.collector.cron:0 * * * * *}")
    public void collectData() {
        if (marketHoursOnly && isMarketClosedNow("collection")) {
            return;
        }

        // Skip this tick if the previous cycle is still running.
        if (!collecting.compareAndSet(false, true)) {
            log.warn("Previous collection cycle still running — skipping this tick to avoid overlap.");
            return;
        }
        try {
            marketCollectorService.collect();
        } finally {
            collecting.set(false);
        }
    }

    @Scheduled(cron = "${nifty.cron-summary:0 */30 * * * *}")
    public void send30MinUpdate() {
        if (marketHoursOnly && isMarketClosedNow("30-min update")) {
            return;
        }

        marketCollectorService.send30MinSummary();
    }

    /**
     * True when the IST market is closed right now: weekend, NSE trading holiday, or outside
     * 09:15–15:30. Holidays come from the configured calendar in {@link com.nifty.analysis.util.TimeUtil}.
     */
    private boolean isMarketClosedNow(String activity) {
        ZonedDateTime nowIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek day = nowIst.getDayOfWeek();
        LocalTime time = nowIst.toLocalTime();

        boolean weekend = (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY);
        boolean holiday = com.nifty.analysis.util.TimeUtil.isExchangeHoliday(nowIst.toLocalDate());
        boolean marketHours = !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 30));

        if (weekend || holiday || !marketHours) {
            log.info("Current IST: {}. Skipping scheduled {} ({}).", nowIst, activity,
                    holiday ? "NSE holiday" : (weekend ? "weekend" : "outside market hours"));
            return true;
        }
        return false;
    }
}
