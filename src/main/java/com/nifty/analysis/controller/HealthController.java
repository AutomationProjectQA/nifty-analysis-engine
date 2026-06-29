package com.nifty.analysis.controller;

import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.service.OnnxModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight health endpoint the frontend (and uptime checks) can poll.
 * Returns 200 when the critical dependency (database) is reachable, 503 otherwise.
 * Redis and model status are reported but non-fatal (model fallback is acceptable).
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OnnxModelService onnxModelService;
    private final RedisConnectionFactory redisConnectionFactory;
    private final com.nifty.analysis.service.DataHealthService dataHealthService;

    @Value("${nifty.risk.trading-enabled:true}")
    private boolean tradingEnabled;

    /** Aggregated data-health verdict: is the data real, fresh, sane, and trading? */
    @GetMapping("/data")
    public ResponseEntity<com.nifty.analysis.service.DataHealthService.Report> dataHealth() {
        com.nifty.analysis.service.DataHealthService.Report r = dataHealthService.report();
        // 200 when healthy, 503 when degraded — so uptime monitors can alert on it directly.
        return ResponseEntity.status("HEALTHY".equals(r.status()) ? 200 : 503).body(r);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();

        boolean dbUp;
        try {
            marketSnapshotRepository.count();
            dbUp = true;
        } catch (Exception e) {
            log.warn("Health check: database unreachable - {}", e.getMessage());
            dbUp = false;
        }
        components.put("database", dbUp ? "UP" : "DOWN");

        boolean redisUp;
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            redisUp = "PONG".equalsIgnoreCase(conn.ping());
        } catch (Exception e) {
            redisUp = false;
        }
        components.put("redis", redisUp ? "UP" : "DOWN");

        components.put("model", onnxModelService.isModelLoaded() ? "LOADED" : "FALLBACK");
        components.put("tradingEnabled", tradingEnabled);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("components", components);
        return ResponseEntity.status(dbUp ? 200 : 503).body(body);
    }
}
