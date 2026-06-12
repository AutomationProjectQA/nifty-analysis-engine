package com.nifty.analysis.controller;

import com.nifty.analysis.entity.AiReport;
import com.nifty.analysis.repository.AiReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final AiReportRepository aiReportRepository;

    @GetMapping("/latest")
    public ResponseEntity<AiReport> getLatestReport(@RequestParam("type") String type) {
        log.info("REST request to fetch latest AI report of type: {}", type);
        return aiReportRepository.findLatestByType(type.toUpperCase())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<Page<AiReport>> getReportHistory(
            @RequestParam("type") String type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        log.info("REST request to fetch AI report history of type: {} (page={}, size={})", type, page, size);
        Page<AiReport> historyPage = aiReportRepository.findByTypeOrderByPublishDateDesc(
                type.toUpperCase(), PageRequest.of(page, size)
        );
        return ResponseEntity.ok(historyPage);
    }
}
