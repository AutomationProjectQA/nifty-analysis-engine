package com.nifty.analysis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase-0 observability: log loudly at startup when configuration that SILENTLY disables a feature
 * is in effect — a blank Gemini key (reports/news fall back to templates), or the live-data /
 * simulated-block combination that hard-blocks trade generation until the broker feed is live.
 * These were previously invisible until someone noticed wrong output in production.
 */
@Component
@Slf4j
public class StartupHealthLogger {

    @Value("${nifty.ai.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${nifty.collector.provider:angelone}")
    private String provider;

    @Value("${nifty.stream.enabled:false}")
    private boolean streamEnabled;

    @Value("${nifty.risk.block-on-simulated-data:true}")
    private boolean blockOnSimulatedData;

    @Value("${nifty.collector.market-hours-only:true}")
    private boolean marketHoursOnly;

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupHealth() {
        log.info("=== Startup health ===");
        log.info("Collector provider={}, live tick stream={}, market-hours-only={}",
                provider, streamEnabled, marketHoursOnly);

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.warn("GEMINI_API_KEY is BLANK — AI reports & news will fall back to static templates. Set nifty.ai.gemini-api-key.");
        } else {
            log.info("Gemini key present (AI reports/news enabled).");
        }

        if ("angelone".equalsIgnoreCase(provider) && blockOnSimulatedData) {
            log.warn("block-on-simulated-data=true: NO trades will be generated until the Angel One feed is confirmed LIVE "
                    + "for each instrument (cold-start/restart/any broker fallback blocks trading). Watch the feed-status chip / "
                    + "/api/v1/market/feed-status.");
        }
        log.info("======================");
    }
}
