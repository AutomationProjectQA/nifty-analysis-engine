package com.nifty.analysis.scheduler;

import com.nifty.analysis.service.ContentGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NewsScheduler {

    private final ContentGenerationService contentGenerationService;

    /** Daily News Intelligence Summarizer: generated at 03:45 PM IST. */
    @Scheduled(cron = "0 45 15 * * *", zone = "Asia/Kolkata")
    public void generateDailyNewsSummary() {
        log.info("NewsScheduler triggering 03:45 PM IST Daily News Summary generation...");
        try {
            contentGenerationService.generateDailyNews();
        } catch (Exception ex) {
            log.error("Error generating daily market news summary", ex);
        }
    }
}
