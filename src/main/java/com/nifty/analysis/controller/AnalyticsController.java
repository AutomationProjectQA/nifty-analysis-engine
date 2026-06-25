package com.nifty.analysis.controller;

import com.nifty.analysis.backtest.BacktestingEngine;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final BacktestingEngine backtestingEngine;
    private final TradeResultRepository tradeResultRepository;
    private final com.nifty.analysis.service.AdaptiveWeightsService adaptiveWeightsService;

    @PostMapping("/optimize")
    public ResponseEntity<String> optimizeWeights() {
        log.info("REST request to manually trigger confidence weights optimization");
        adaptiveWeightsService.tuneWeights();
        return ResponseEntity.ok("Weights optimization complete");
    }

    @PostMapping("/backtest/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        log.info("Request to run backtest between {} and {}", start, end);
        Map<String, Object> results = backtestingEngine.runBacktest(start, end);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
            @RequestParam(value = "start", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(value = "end", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        List<TradeResult> results = (start != null && end != null)
                ? tradeResultRepository.findBySignalTimeBetween(start, end)
                : tradeResultRepository.findAll();

        long totalTrades = results.size();
        long target1Hits = results.stream().filter(r -> "TARGET1".equals(r.getOutcome())).count();
        long target2Hits = results.stream().filter(r -> "TARGET2".equals(r.getOutcome())).count();
        long slHits = results.stream().filter(r -> "STOP_LOSS".equals(r.getOutcome())).count();
        long expired = results.stream().filter(r -> "EXPIRED".equals(r.getOutcome())).count();
        
        double totalPnL = results.stream().mapToDouble(TradeResult::getProfitLoss).sum();
        double winRate = totalTrades > 0 ? (double) (target1Hits + target2Hits) / (totalTrades) * 100.0 : 0.0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalTrades", totalTrades);
        summary.put("target1Hits", target1Hits);
        summary.put("target2Hits", target2Hits);
        summary.put("stopLossHits", slHits);
        summary.put("expiredTrades", expired);
        summary.put("totalProfitLossInr", Math.round(totalPnL * 100.0) / 100.0);
        summary.put("winRatePercentage", Math.round(winRate * 100.0) / 100.0);

        return ResponseEntity.ok(summary);
    }
}
