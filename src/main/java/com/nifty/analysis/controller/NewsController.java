package com.nifty.analysis.controller;

import com.nifty.analysis.entity.MarketNews;
import com.nifty.analysis.repository.MarketNewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
@Slf4j
public class NewsController {

    private final MarketNewsRepository marketNewsRepository;

    @GetMapping("/today")
    public ResponseEntity<List<MarketNews>> getTodayNews() {
        log.info("REST request to fetch latest Nifty market news summary");
        List<MarketNews> news = marketNewsRepository.findTop5ByOrderByPublishedAtDesc();
        return ResponseEntity.ok(news);
    }

    @GetMapping("/history")
    public ResponseEntity<Page<MarketNews>> getNewsHistory(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        log.info("REST request to fetch Nifty news history (page={}, size={})", page, size);
        Page<MarketNews> historyPage = marketNewsRepository.findAllByOrderByPublishedAtDesc(PageRequest.of(page, size));
        return ResponseEntity.ok(historyPage);
    }
}
