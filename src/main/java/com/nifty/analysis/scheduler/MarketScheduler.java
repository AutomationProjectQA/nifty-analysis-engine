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

    @Value("${nifty.collector.market-hours-only:false}")
    private boolean marketHoursOnly;

    @Scheduled(cron = "${nifty.collector.cron:0 * * * * *}")
    public void collectData() {
        if (marketHoursOnly) {
            ZonedDateTime nowIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            DayOfWeek day = nowIst.getDayOfWeek();
            LocalTime time = nowIst.toLocalTime();
            
            boolean isWeekday = (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY);
            boolean isMarketHours = !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 30));
            
            if (!isWeekday || !isMarketHours) {
                log.info("Current IST: {}. Skipping scheduled collection (outside Indian market hours).", nowIst);
                return;
            }
        }
        
        marketCollectorService.collect();
    }

    @Scheduled(cron = "${nifty.cron-summary:0 */30 * * * *}")
    public void send30MinUpdate() {
        if (marketHoursOnly) {
            ZonedDateTime nowIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            DayOfWeek day = nowIst.getDayOfWeek();
            LocalTime time = nowIst.toLocalTime();
            
            boolean isWeekday = (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY);
            boolean isMarketHours = !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 30));
            
            if (!isWeekday || !isMarketHours) {
                log.info("Current IST: {}. Skipping scheduled 30-min update (outside Indian market hours).", nowIst);
                return;
            }
        }
        
        marketCollectorService.send30MinSummary();
    }
}
