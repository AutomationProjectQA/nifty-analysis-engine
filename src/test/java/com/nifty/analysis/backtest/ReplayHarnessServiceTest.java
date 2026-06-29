package com.nifty.analysis.backtest;

import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.config.TradingPolicy;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.OnnxModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Replay harness: non-persisting A/B over history; a higher gate must produce no more signals. */
class ReplayHarnessServiceTest {

    private MarketSnapshotRepository marketSnapshotRepository;
    private OptionSnapshotRepository optionSnapshotRepository;
    private ConfidenceEngine confidenceEngine;
    private CriticAgent criticAgent;
    private OnnxModelService onnxModelService;
    private TechnicalAgent technicalAgent;
    private TradingPolicy tradingPolicy;
    private ReplayHarnessService harness;

    private final LocalDateTime start = LocalDateTime.of(2026, 6, 23, 9, 15);
    private final LocalDateTime end = LocalDateTime.of(2026, 6, 23, 15, 30);

    @BeforeEach
    void setUp() {
        marketSnapshotRepository = Mockito.mock(MarketSnapshotRepository.class);
        optionSnapshotRepository = Mockito.mock(OptionSnapshotRepository.class);
        confidenceEngine = Mockito.mock(ConfidenceEngine.class);
        criticAgent = Mockito.mock(CriticAgent.class);
        onnxModelService = Mockito.mock(OnnxModelService.class);
        technicalAgent = Mockito.mock(TechnicalAgent.class);
        tradingPolicy = new TradingPolicy();
        ReflectionTestUtils.setField(tradingPolicy, "gatingThreshold", 60.0);
        ReflectionTestUtils.setField(tradingPolicy, "targetProfitPercent", 2.0);
        ReflectionTestUtils.setField(tradingPolicy, "stopLossPercent", 40.0);

        harness = new ReplayHarnessService(marketSnapshotRepository, optionSnapshotRepository,
                confidenceEngine, criticAgent, onnxModelService, technicalAgent, tradingPolicy);
        ReflectionTestUtils.setField(harness, "modelWeight", 0.4);
        ReflectionTestUtils.setField(harness, "lotSize", 65);
        ReflectionTestUtils.setField(harness, "entryPremium", 150.0);
        ReflectionTestUtils.setField(harness, "brokeragePerTrade", 40.0);
        ReflectionTestUtils.setField(harness, "slippagePercent", 0.5);
        ReflectionTestUtils.setField(harness, "thetaDecayPerHour", 0.5);

        // 6 snapshots, spot rising 23500 → 23550.
        List<MarketSnapshot> snaps = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            MarketSnapshot s = new MarketSnapshot();
            s.setInstrument("NIFTY");
            s.setSnapshotTime(start.plusMinutes(k));
            s.setNiftySpot(23500.0 + k * 10);
            s.setNiftyFuture(23530.0 + k * 10);
            s.setIndiaVix(12.0);
            snaps.add(s);
        }
        when(marketSnapshotRepository.findAll()).thenReturn(snaps);

        OptionSnapshot o = new OptionSnapshot();
        o.setStrikePrice(23500);
        o.setCeOi(1_000_000L);
        o.setPeOi(1_000_000L);
        lenient().when(optionSnapshotRepository.findBySnapshotTime(any())).thenReturn(List.of(o));

        // A consistently bullish, high-confidence setup so the gate decides signal count.
        lenient().when(technicalAgent.analyze(any())).thenReturn(new AgentResponse(80.0, "BULLISH", List.of()));
        lenient().when(technicalAgent.getFeatures(any(), anyList()))
                .thenReturn(new TechnicalAgent.TechnicalFeatures(60, 1.0, 1.0, 12, 0.0, 0.02, 0.5, 1.2));
        lenient().when(onnxModelService.predictBullishProbability(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(80.0);
        lenient().when(confidenceEngine.calculateRawConfidence(any(), anyList(), anyDouble(), anyBoolean()))
                .thenReturn(new ConfidenceEngine.RawConfidenceResult(80.0, Map.of("Trend", 80.0)));
        lenient().when(criticAgent.evaluateAndApplyPenalties(anyDouble(), any(), anyList(), anyBoolean()))
                .thenReturn(new CriticAgent.CriticResult(80.0, List.of()));
    }

    @Test
    void compareGating_higherGateProducesNoMoreSignals_andNoPersistence() {
        // Candidate gate 90 > final confidence 80 → candidate emits zero; live gate 60 emits some.
        ReplayHarnessService.ComparisonReport rep = harness.compareGating(start, end, 90.0);

        assertEquals(2, rep.runs().size());
        ReplayHarnessService.ReplayRun current = rep.runs().get(0);
        ReplayHarnessService.ReplayRun candidate = rep.runs().get(1);

        assertEquals(60.0, current.gatingThreshold());
        assertEquals(90.0, candidate.gatingThreshold());
        assertTrue(current.totalSignals() > 0, "live gate should generate signals");
        assertEquals(0, candidate.totalSignals(), "gate above confidence should generate none");
        assertTrue(current.totalSignals() >= candidate.totalSignals());
        // Structural guarantee: the harness has no signal/result repositories, so it cannot persist.
        assertNotNull(current.metrics());
    }
}
