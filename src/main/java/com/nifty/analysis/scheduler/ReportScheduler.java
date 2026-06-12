package com.nifty.analysis.scheduler;

import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.AiReport;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.AiReportRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.LlmService;
import com.nifty.analysis.service.OptionsIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReportScheduler {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final AiReportRepository aiReportRepository;
    private final LlmService llmService;
    private final OptionsIndicatorService optionsIndicatorService;

    /**
     * Pre-Market Morning View Vlog: Automatically generates and posts at 07:00 AM IST.
     */
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    public void generatePreMarketReport() {
        log.info("ReportScheduler triggering 07:00 AM IST Pre-Market Market View generation...");
        try {
            LocalDate today = LocalDate.now();
            Optional<AiReport> existing = aiReportRepository.findByTypeAndPublishDate("PRE_MARKET", today);
            if (existing.isPresent()) {
                log.info("Pre-market report for today {} already exists. Skipping.", today);
                return;
            }

            Optional<MarketSnapshot> latestOpt = marketSnapshotRepository.findLatest();
            double lastSpot = latestOpt.map(MarketSnapshot::getNiftySpot).orElse(23500.0);
            double lastVix = latestOpt.map(MarketSnapshot::getIndiaVix).orElse(13.0);

            List<OptionSnapshot> optionChain = getLatestOptionChain();

            String reportText = llmService.generatePreMarketReport(lastSpot, lastVix, optionChain);

            AiReport report = new AiReport();
            report.setType("PRE_MARKET");
            report.setPublishDate(today);
            report.setReportText(reportText);
            report.setGeneratedAt(LocalDateTime.now());

            aiReportRepository.save(report);
            log.info("Saved Pre-Market Morning View vlog for {}", today);
        } catch (Exception ex) {
            log.error("Error generating pre-market report", ex);
        }
    }

    /**
     * Post-Market Daily Update Vlog: Automatically generates and posts at 03:35 PM IST.
     */
    @Scheduled(cron = "0 35 15 * * *", zone = "Asia/Kolkata")
    public void generatePostMarketReport() {
        log.info("ReportScheduler triggering 03:35 PM IST Post-Market Daily Update generation...");
        try {
            LocalDate today = LocalDate.now();
            Optional<AiReport> existing = aiReportRepository.findByTypeAndPublishDate("POST_MARKET", today);
            if (existing.isPresent()) {
                log.info("Post-market report for today {} already exists. Skipping.", today);
                return;
            }

            Optional<MarketSnapshot> latestOpt = marketSnapshotRepository.findLatest();
            if (latestOpt.isEmpty()) {
                log.warn("No market snapshot found for post-market summary generation. Skipping.");
                return;
            }

            MarketSnapshot latest = latestOpt.get();
            List<OptionSnapshot> optionChain = getLatestOptionChain();
            List<OptionSnapshotDto> dtos = mapToDtos(optionChain);

            double pcr = optionsIndicatorService.calculateOverallPcr(dtos);
            double maxPain = optionsIndicatorService.calculateMaxPain(dtos);

            String reportText = llmService.generatePostMarketReport(latest, pcr, maxPain, optionChain);

            AiReport report = new AiReport();
            report.setType("POST_MARKET");
            report.setPublishDate(today);
            report.setReportText(reportText);
            report.setGeneratedAt(LocalDateTime.now());

            aiReportRepository.save(report);
            log.info("Saved Post-Market Daily Update vlog for {}", today);
        } catch (Exception ex) {
            log.error("Error generating post-market report", ex);
        }
    }

    private List<OptionSnapshot> getLatestOptionChain() {
        LocalDateTime latestOptionTime = optionSnapshotRepository.findLatestSnapshotTime();
        if (latestOptionTime != null) {
            return optionSnapshotRepository.findBySnapshotTime(latestOptionTime);
        }
        return Collections.emptyList();
    }

    private List<OptionSnapshotDto> mapToDtos(List<OptionSnapshot> entities) {
        return entities.stream().map(o -> new OptionSnapshotDto(
                o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                o.getIv(), o.getPcr(), o.getMaxPain(), o.getCeVolume(), o.getPeVolume(), o.getSnapshotTime()
        )).toList();
    }
}
