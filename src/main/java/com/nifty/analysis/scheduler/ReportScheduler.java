package com.nifty.analysis.scheduler;

import com.nifty.analysis.service.ContentGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReportScheduler {

    private final ContentGenerationService contentGenerationService;

    /** Pre-Market Morning View: generated at 07:00 AM IST (skips if today's already exists). */
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    public void generatePreMarketReport() {
        log.info("ReportScheduler triggering 07:00 AM IST Pre-Market report generation...");
        try {
            contentGenerationService.generatePreMarketReport(false);
        } catch (Exception ex) {
            log.error("Error generating pre-market report", ex);
        }
    }

    /** Post-Market Daily Update: generated at 03:35 PM IST (skips if today's already exists). */
    @Scheduled(cron = "0 35 15 * * *", zone = "Asia/Kolkata")
    public void generatePostMarketReport() {
        log.info("ReportScheduler triggering 03:35 PM IST Post-Market report generation...");
        try {
            contentGenerationService.generatePostMarketReport(false);
        } catch (Exception ex) {
            log.error("Error generating post-market report", ex);
        }
    }
}
