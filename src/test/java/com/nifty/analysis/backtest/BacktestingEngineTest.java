package com.nifty.analysis.backtest;

import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.service.OnnxModelService;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BacktestingEngineTest {

    @Mock
    private MarketSnapshotRepository marketSnapshotRepository;
    @Mock
    private OptionSnapshotRepository optionSnapshotRepository;
    @Mock
    private ConfidenceEngine confidenceEngine;
    @Mock
    private CriticAgent criticAgent;
    @Mock
    private TradeSignalRepository tradeSignalRepository;
    @Mock
    private TradeResultRepository tradeResultRepository;
    @Mock
    private OnnxModelService onnxModelService;
    @Mock
    private TechnicalAgent technicalAgent;

    private BacktestingEngine backtestingEngine;

    @BeforeEach
    void setUp() {
        backtestingEngine = new BacktestingEngine(
                marketSnapshotRepository,
                optionSnapshotRepository,
                confidenceEngine,
                criticAgent,
                tradeSignalRepository,
                tradeResultRepository,
                onnxModelService,
                technicalAgent
        );
    }

    @Test
    void testRunBacktestInsufficientData() {
        when(marketSnapshotRepository.findAll()).thenReturn(Collections.emptyList());
        
        Map<String, Object> results = backtestingEngine.runBacktest(LocalDateTime.now().minusDays(1), LocalDateTime.now());
        
        assertEquals("FAILED", results.get("status"));
        assertEquals("Insufficient historical market snapshots in range", results.get("error"));
    }

    @Test
    void testRunBacktestSuccess() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MarketSnapshot s1 = new MarketSnapshot();
        s1.setSnapshotTime(now.minusMinutes(10));
        s1.setNiftySpot(23500.0);
        s1.setNiftyFuture(23530.0);
        s1.setEma20(23490.0);
        s1.setEma50(23480.0);
        s1.setIndiaVix(12.0);

        MarketSnapshot s2 = new MarketSnapshot();
        s2.setSnapshotTime(now.minusMinutes(5));
        s2.setNiftySpot(23560.0); // Price moved up! (should hit target)
        s2.setNiftyFuture(23590.0);
        s2.setEma20(23500.0);
        s2.setEma50(23485.0);
        s2.setIndiaVix(12.0);

        when(marketSnapshotRepository.findAll()).thenReturn(List.of(s1, s2));

        OptionSnapshot opt = new OptionSnapshot();
        opt.setSnapshotTime(s1.getSnapshotTime());
        opt.setStrikePrice(23500);
        opt.setCeOi(100000L);
        opt.setPeOi(120000L);
        opt.setPcr(1.2);
        
        when(optionSnapshotRepository.findBySnapshotTime(any(LocalDateTime.class)))
                .thenReturn(List.of(opt));

        TechnicalAgent.TechnicalFeatures mockFeatures = new TechnicalAgent.TechnicalFeatures(50.0, 1.0, 1.0, 15.0, 0.0);
        when(technicalAgent.getFeatures(any())).thenReturn(mockFeatures);
        when(onnxModelService.predictBullishProbability(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(85.0);
        
        CriticAgent.CriticResult critRes = new CriticAgent.CriticResult(85.0, Collections.emptyList());
        when(criticAgent.evaluateAndApplyPenalties(anyDouble(), any(), anyList(), anyBoolean())).thenReturn(critRes);

        // Act
        Map<String, Object> results = backtestingEngine.runBacktest(now.minusMinutes(15), now);

        // Assert
        assertEquals("SUCCESS", results.get("status"));
        assertEquals(1, results.get("totalSignals"));
        verify(tradeSignalRepository, atLeastOnce()).save(any(TradeSignal.class));
        verify(tradeResultRepository, atLeastOnce()).save(any(TradeResult.class));
    }
}
