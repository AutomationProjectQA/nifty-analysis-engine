package com.nifty.analysis.controller;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.MarketCollectorService;
import com.nifty.analysis.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class MarketController {

    private final MarketCollectorService marketCollectorService;
    private final RedisService redisService;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;

    @PostMapping("/market/collect")
    public ResponseEntity<String> forceCollect() {
        log.info("REST request to manually trigger data collection");
        marketCollectorService.collect();
        return ResponseEntity.ok("Data collection triggered successfully");
    }

    @GetMapping("/market/latest")
    public ResponseEntity<MarketSnapshot> getLatestMarket() {
        return redisService.getLatestMarketSnapshot()
                .or(marketSnapshotRepository::findLatest)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/options/latest")
    public ResponseEntity<List<OptionSnapshot>> getLatestOptions() {
        List<OptionSnapshot> cached = redisService.getLatestOptionChain();
        if (!cached.isEmpty()) {
            return ResponseEntity.ok(cached);
        }

        LocalDateTime latestTime = optionSnapshotRepository.findLatestSnapshotTime();
        if (latestTime != null) {
            List<OptionSnapshot> dbList = optionSnapshotRepository.findBySnapshotTime(latestTime);
            return ResponseEntity.ok(dbList);
        }

        return ResponseEntity.notFound().build();
    }
}
