package com.nifty.analysis.controller;

import com.nifty.analysis.entity.AiReport;
import com.nifty.analysis.repository.AiReportRepository;
import com.nifty.analysis.service.ContentGenerationService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Validated
@Slf4j
public class ReportController {

    private final AiReportRepository aiReportRepository;
    private final ContentGenerationService contentGenerationService;

    /** Manually generate a report now (on-demand), bypassing the once-per-day schedule. */
    @PostMapping("/generate")
    public ResponseEntity<AiReport> generateReport(@RequestParam("type") @NotBlank String type) {
        String normalized = type.toUpperCase();
        log.info("REST request to manually generate {} report", normalized);
        AiReport report;
        if ("PRE_MARKET".equals(normalized)) {
            report = contentGenerationService.generatePreMarketReport(true);
        } else if ("POST_MARKET".equals(normalized)) {
            report = contentGenerationService.generatePostMarketReport(true);
        } else {
            return ResponseEntity.badRequest().build();
        }
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.unprocessableEntity().build();
    }

    @GetMapping("/latest")
    public ResponseEntity<AiReport> getLatestReport(@RequestParam("type") @NotBlank String type) {
        log.info("REST request to fetch latest AI report of type: {}", type);
        return aiReportRepository.findLatestByType(type.toUpperCase())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<Page<AiReport>> getReportHistory(
            @RequestParam("type") @NotBlank String type,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        log.info("REST request to fetch AI report history of type: {} (page={}, size={})", type, page, size);
        Page<AiReport> historyPage = aiReportRepository.findByTypeOrderByPublishDateDesc(
                type.toUpperCase(), PageRequest.of(page, size)
        );
        return ResponseEntity.ok(historyPage);
    }
}
