package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.impl.NewsRssClient;
import com.nifty.analysis.collector.client.impl.YahooFinanceClient;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.AiReport;
import com.nifty.analysis.entity.MarketNews;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.AiReportRepository;
import com.nifty.analysis.repository.MarketNewsRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Generates and persists AI market reports and news summaries via Gemini.
 * Used by both the schedulers (timed runs) and the manual trigger endpoints
 * (on-demand generation for testing / immediate population).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentGenerationService {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final AiReportRepository aiReportRepository;
    private final MarketNewsRepository marketNewsRepository;
    private final LlmService llmService;
    private final OptionsIndicatorService optionsIndicatorService;
    private final YahooFinanceClient yahooFinanceClient;
    private final NewsRssClient newsRssClient;

    /** Generates the pre-market report. If !force and one already exists for today, returns it unchanged. */
    public AiReport generatePreMarketReport(boolean force) {
        LocalDate today = LocalDate.now();
        Optional<AiReport> existing = aiReportRepository.findByTypeAndPublishDate("PRE_MARKET", today);
        if (existing.isPresent() && !force) {
            log.info("Pre-market report for {} already exists. Skipping.", today);
            return existing.get();
        }

        Optional<MarketSnapshot> latestOpt = marketSnapshotRepository.findLatest();
        double lastSpot = latestOpt.map(MarketSnapshot::getNiftySpot).orElse(23500.0);
        double lastVix = latestOpt.map(MarketSnapshot::getIndiaVix).orElse(13.0);
        List<OptionSnapshot> optionChain = getLatestOptionChain();

        String reportText = llmService.generatePreMarketReport(lastSpot, lastVix, optionChain);
        AiReport report = existing.orElseGet(AiReport::new);
        report.setType("PRE_MARKET");
        report.setPublishDate(today);
        report.setReportText(reportText);
        report.setGeneratedAt(LocalDateTime.now());
        aiReportRepository.save(report);
        log.info("Saved Pre-Market report for {}", today);
        return report;
    }

    /** Generates the post-market report. Returns null if there is no market snapshot to base it on. */
    public AiReport generatePostMarketReport(boolean force) {
        LocalDate today = LocalDate.now();
        Optional<AiReport> existing = aiReportRepository.findByTypeAndPublishDate("POST_MARKET", today);
        if (existing.isPresent() && !force) {
            log.info("Post-market report for {} already exists. Skipping.", today);
            return existing.get();
        }

        Optional<MarketSnapshot> latestOpt = marketSnapshotRepository.findLatest();
        if (latestOpt.isEmpty()) {
            log.warn("No market snapshot available for post-market report. Skipping.");
            return null;
        }

        MarketSnapshot latest = latestOpt.get();
        List<OptionSnapshot> optionChain = getLatestOptionChain();
        List<OptionSnapshotDto> dtos = mapToDtos(optionChain);
        double pcr = optionsIndicatorService.calculateOverallPcr(dtos);
        double maxPain = optionsIndicatorService.calculateMaxPain(dtos);

        String reportText = llmService.generatePostMarketReport(latest, pcr, maxPain, optionChain);
        AiReport report = existing.orElseGet(AiReport::new);
        report.setType("POST_MARKET");
        report.setPublishDate(today);
        report.setReportText(reportText);
        report.setGeneratedAt(LocalDateTime.now());
        aiReportRepository.save(report);
        log.info("Saved Post-Market report for {}", today);
        return report;
    }

    /**
     * Generates and saves fresh market news. Pulls REAL headlines from financial RSS feeds,
     * persists each new one as its own item (with a working source link), then produces an
     * AI 'Top 5 Events' summary grounded in those headlines plus live global cues. No fabricated
     * FII number; GIFT Nifty is passed only as a labelled estimate. Returns the summary item.
     */
    public MarketNews generateDailyNews() {
        List<NewsRssClient.Headline> headlines = newsRssClient.fetchTopHeadlines(8);

        // 1. Persist each genuinely-new headline as its own news item (deduped by title).
        for (NewsRssClient.Headline h : headlines) {
            if (marketNewsRepository.existsByTitle(h.title())) {
                continue;
            }
            MarketNews item = new MarketNews();
            item.setTitle(h.title());
            item.setSummary(h.description() != null && !h.description().isBlank()
                    ? h.description() : h.title());
            item.setImportance("MEDIUM");
            item.setPublishedAt(h.publishedAt() != null ? h.publishedAt() : LocalDateTime.now());
            item.setSourceUrl(h.link() != null ? h.link() : "");
            marketNewsRepository.save(item);
        }

        // 2. AI 'Top 5 Events' summary, grounded in the real headlines + live global cues.
        YahooFinanceClient.SentimentData liveData = yahooFinanceClient.fetchLiveSentimentData();
        String newsSummary = llmService.generateMarketNews(
                headlines,
                liveData.giftNiftyPremium(),
                liveData.dowFuturesPoints(),
                liveData.dollarIndex(),
                liveData.crudeOil()
        );

        MarketNews newsItem = new MarketNews();
        newsItem.setTitle("Top 5 Events Impacting Nifty Today");
        newsItem.setSummary(newsSummary);
        newsItem.setImportance("HIGH");
        newsItem.setPublishedAt(LocalDateTime.now()); // newest, so it sorts to the top of /today
        newsItem.setSourceUrl("");
        marketNewsRepository.save(newsItem);
        log.info("Saved daily market news: {} headlines + 1 AI summary", headlines.size());
        return newsItem;
    }

    private List<OptionSnapshot> getLatestOptionChain() {
        LocalDateTime latestOptionTime = optionSnapshotRepository.findLatestSnapshotTime();
        return latestOptionTime != null
                ? optionSnapshotRepository.findBySnapshotTime(latestOptionTime)
                : Collections.emptyList();
    }

    private List<OptionSnapshotDto> mapToDtos(List<OptionSnapshot> entities) {
        return entities.stream().map(o -> new OptionSnapshotDto(
                o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                o.getIv(), o.getPcr(), o.getMaxPain(), o.getCeVolume(), o.getPeVolume(), o.getSnapshotTime(),
                o.getCeLtp(), o.getPeLtp()
        )).toList();
    }
}
