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
}
