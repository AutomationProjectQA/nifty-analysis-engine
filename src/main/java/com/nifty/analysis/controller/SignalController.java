package com.nifty.analysis.controller;

import com.nifty.analysis.entity.DecisionTrace;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.DecisionTraceRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/signals")
@RequiredArgsConstructor
@Slf4j
public class SignalController {

    private final TradeSignalRepository tradeSignalRepository;
    private final DecisionTraceRepository decisionTraceRepository;
    private final com.nifty.analysis.service.CalibrationMonitorService calibrationMonitorService;
    private final com.nifty.analysis.service.FactorEffectivenessService factorEffectivenessService;
    private final com.nifty.analysis.service.DriftMonitorService driftMonitorService;
    private final com.nifty.analysis.backtest.ReplayHarnessService replayHarnessService;

    @GetMapping
    public ResponseEntity<List<TradeSignal>> getAllSignals() {
        log.info("REST request to fetch all options buy signals");
        List<TradeSignal> signals = tradeSignalRepository.findAllByOrderBySignalTimeDesc();
        return ResponseEntity.ok(signals);
    }

    @GetMapping("/active")
    public ResponseEntity<List<TradeSignal>> getActiveSignals() {
        log.info("REST request to fetch active options buy signals");
        List<TradeSignal> activeSignals = tradeSignalRepository.findByStatus("ACTIVE");
        return ResponseEntity.ok(activeSignals);
    }

    /** Phase-0 observability: the most recent decision-evaluation traces (why trades did/didn't fire). */
    @GetMapping("/decision-traces")
    public ResponseEntity<List<DecisionTrace>> getDecisionTraces(
            @RequestParam(value = "instrument", required = false) String instrument) {
        List<DecisionTrace> traces = (instrument == null || instrument.isBlank())
                ? decisionTraceRepository.findTop100ByOrderByEvaluationTimeDesc()
                : decisionTraceRepository.findTop100ByInstrumentOrderByEvaluationTimeDesc(instrument);
        return ResponseEntity.ok(traces);
    }

    /**
     * Trade-generation funnel for today: how many evaluations were EMITTED vs REJECTED, broken
     * down by the gate that rejected them. This is the single most useful "why no trades" view.
     */
    @GetMapping("/decision-funnel")
    public ResponseEntity<Map<String, Object>> getDecisionFunnel() {
        LocalDate today = com.nifty.analysis.util.TimeUtil.todayIst();
        List<Object[]> rows = decisionTraceRepository.funnelSince(today.atStartOfDay());
        long total = 0, emitted = 0;
        List<Map<String, Object>> byStage = new ArrayList<>();
        for (Object[] r : rows) {
            String outcome = (String) r[0];
            String stage = (String) r[1];
            long count = ((Number) r[2]).longValue();
            total += count;
            if ("EMITTED".equals(outcome)) emitted += count;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome);
            m.put("rejectStage", stage);
            m.put("count", count);
            byStage.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today.toString());
        out.put("totalEvaluations", total);
        out.put("emitted", emitted);
        out.put("rejected", total - emitted);
        out.put("breakdown", byStage);
        return ResponseEntity.ok(out);
    }

    /**
     * Phase-4 adaptive-AI observability: confidence calibration report — per-confidence-bucket
     * realised win-rate vs the calibrator's modelled probability. Answers "does predicted 80%
     * actually win ~80%?". Read-only; does not affect trading.
     */
    @GetMapping("/calibration")
    public ResponseEntity<com.nifty.analysis.service.CalibrationMonitorService.Report> getCalibration() {
        return ResponseEntity.ok(calibrationMonitorService.report());
    }

    /**
     * Phase-4 adaptive-AI observability: which confidence factors actually separate winners from
     * losers (avg score on wins vs losses, sorted by edge). Use it to prune/down-weight noisy
     * factors. Read-only.
     */
    @GetMapping("/factor-effectiveness")
    public ResponseEntity<com.nifty.analysis.service.FactorEffectivenessService.Report> getFactorEffectiveness() {
        return ResponseEntity.ok(factorEffectivenessService.report());
    }

    /**
     * Phase-4 adaptive-AI observability: concept-drift signal — recent vs historical win-rate and
     * confidence shift, with a {@code degraded} flag when recent performance falls off. Read-only.
     */
    @GetMapping("/drift")
    public ResponseEntity<com.nifty.analysis.service.DriftMonitorService.Report> getDrift() {
        return ResponseEntity.ok(driftMonitorService.report());
    }

    /**
     * Phase-4 replay harness (#215): non-persisting A/B of the live policy vs a candidate gating
     * threshold over a historical window — safe experimentation before changing config in prod.
     * Example: /api/v1/signals/replay-compare?start=2026-06-01T09:15:00&end=2026-06-27T15:30:00&candidateGate=55
     */
    @GetMapping("/replay-compare")
    public ResponseEntity<com.nifty.analysis.backtest.ReplayHarnessService.ComparisonReport> replayCompare(
            @org.springframework.web.bind.annotation.RequestParam
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @org.springframework.web.bind.annotation.RequestParam
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @org.springframework.web.bind.annotation.RequestParam(value = "candidateGate") double candidateGate) {
        return ResponseEntity.ok(replayHarnessService.compareGating(start, end, candidateGate));
    }
}
