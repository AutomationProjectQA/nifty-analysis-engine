package com.nifty.analysis.service;

import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriftMonitorServiceTest {

    @Mock private TradeResultRepository tradeResultRepository;
    @Mock private SignalExplanationRepository signalExplanationRepository;

    private DriftMonitorService service;

    @BeforeEach
    void setUp() {
        service = new DriftMonitorService(tradeResultRepository, signalExplanationRepository);
        ReflectionTestUtils.setField(service, "driftWindow", 10);
        ReflectionTestUtils.setField(service, "degradeThreshold", 0.15);
        lenient().when(signalExplanationRepository.findBySignalIdIn(anyList())).thenReturn(List.of());
    }

    private TradeResult result(long id, double pnl) {
        TradeSignal s = new TradeSignal();
        s.setId(id);
        TradeResult r = new TradeResult();
        r.setId(id);
        r.setSignal(s);
        r.setProfitLoss(pnl);
        return r;
    }

    @Test
    void emptyData_notDegraded() {
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        DriftMonitorService.Report rep = service.report();
        assertEquals(0, rep.totalResolved());
        assertFalse(rep.degraded());
    }

    @Test
    void recentSlumpVsStrongHistory_flagsDegraded() {
        // First 10 trades all wins (historical strong); last 10 mostly losses (recent slump).
        List<TradeResult> all = new ArrayList<>();
        for (long id = 1; id <= 10; id++) all.add(result(id, 100.0));      // wins
        for (long id = 11; id <= 20; id++) all.add(result(id, id % 2 == 0 ? 100.0 : -100.0)); // 5 win / 5 loss
        when(tradeResultRepository.findAll()).thenReturn(all);

        DriftMonitorService.Report rep = service.report();

        assertEquals(20, rep.totalResolved());
        assertEquals(10, rep.recentWindow());
        assertEquals(0.75, rep.overallWinRate(), 0.001);  // 15 of 20
        assertEquals(0.5, rep.recentWinRate(), 0.001);     // 5 of last 10
        assertTrue(rep.winRateDrift() < 0);                // recent below history
        assertTrue(rep.degraded());                        // 0.25 drop >= 0.15 threshold
    }

    @Test
    void stablePerformance_notDegraded() {
        List<TradeResult> all = new ArrayList<>();
        for (long id = 1; id <= 20; id++) all.add(result(id, id % 2 == 0 ? 100.0 : -100.0)); // steady 50%
        when(tradeResultRepository.findAll()).thenReturn(all);

        DriftMonitorService.Report rep = service.report();

        assertEquals(0.5, rep.overallWinRate(), 0.001);
        assertEquals(0.5, rep.recentWinRate(), 0.001);
        assertFalse(rep.degraded());
    }
}
