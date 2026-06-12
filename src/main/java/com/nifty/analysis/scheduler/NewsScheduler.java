package com.nifty.analysis.scheduler;

import com.nifty.analysis.collector.client.impl.YahooFinanceClient;
import com.nifty.analysis.entity.MarketNews;
import com.nifty.analysis.repository.MarketNewsRepository;
import com.nifty.analysis.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class NewsScheduler {

    private final YahooFinanceClient yahooFinanceClient;
    private final MarketNewsRepository marketNewsRepository;
    private final LlmService llmService;

    /**
     * Daily News Intelligence Summarizer: Automatically aggregates and posts at 03:45 PM IST.
     */
    @Scheduled(cron = "0 45 15 * * *", zone = "Asia/Kolkata")
    public void generateDailyNewsSummary() {
        log.info("NewsScheduler triggering 03:45 PM IST Daily News Summary generation...");
        try {
            YahooFinanceClient.SentimentData liveData = yahooFinanceClient.fetchLiveSentimentData();
            
            // FII flows default to 450.0 Cr or dynamic
            double fiiFlow = 450.0; 

            String newsSummary = llmService.generateMarketNews(
                    liveData.giftNiftyPremium(),
                    liveData.dowFuturesPoints(),
                    liveData.dollarIndex(),
                    liveData.crudeOil(),
                    fiiFlow
            );

            MarketNews newsItem = new MarketNews();
            newsItem.setTitle("Top 5 Events Impacting Nifty Today");
            newsItem.setSummary(newsSummary);
            newsItem.setImportance("HIGH");
            newsItem.setPublishedAt(LocalDateTime.now());
            newsItem.setSourceUrl("https://finance.yahoo.com");

            marketNewsRepository.save(newsItem);
            log.info("Saved daily Nifty Market News summary successfully");
        } catch (Exception ex) {
            log.error("Error generating daily market news summary", ex);
        }
    }
}
