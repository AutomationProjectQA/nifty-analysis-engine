package com.nifty.analysis.service;

import com.nifty.analysis.engine.ConfidenceCalibrator;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalibrationMonitorServiceTest {

    @Mock private TradeResultRepository tradeResultRepository;
    @Mock private SignalExplanationRepository signalExplanationRepository;
    @Mock private ConfidenceCalibrator calibrator;

    private CalibrationMonitorService monitor;

    @BeforeEach
    void setUp() {
        monitor = new CalibrationMonitorService(tradeResultRepository, signalExplanationRepository, calibrator);
        lenient().when(calibrator.isTrained()).thenReturn(false);
        lenient().when(calibrator.sampleCount()).thenReturn(0);
        lenient().when(calibrator.breakEvenWinRate()).thenReturn(0.91);
        lenient().when(calibrator.probabilityOfWin(org.mockito.ArgumentMatchers.anyDouble())).thenReturn(-1.0);
    }

    private TradeResult result(long signalId, double pnl) {
        TradeSignal s = new TradeSignal();
        s.setId(signalId);
        TradeResult r = new TradeResult();
        r.setSignal(s);
        r.setProfitLoss(pnl);
        return r;
    }

    private SignalExplanation conf(long signalId, double finalConf) {
        TradeSignal s = new TradeSignal();
        s.setId(signalId);
        SignalExplanation e = new SignalExplanation();
        e.setSignal(s);
        e.setFactor("Final_Confidence");
        e.setScore(finalConf);
        return e;
    }

    @Test
    void emptyData_reportsZeroes() {
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        CalibrationMonitorService.Report rep = monitor.report();
        assertEquals(0, rep.totalResolved());
        assertEquals(0.0, rep.overallWinRate(), 0.0001);
        assertFalse(rep.buckets().isEmpty()); // bucket scaffold still present
    }

    @Test
    void bucketsRealisedWinRate_perConfidenceBand() {
        // Two trades in the 80-90% band: one win, one loss → actual win-rate 0.5.
        // One trade in the 60-70% band: a win → 1.0.
        when(tradeResultRepository.findAll()).thenReturn(List.of(
                result(1, 100.0), result(2, -50.0), result(3, 30.0)));
        when(signalExplanationRepository.findBySignalIdIn(anyList())).thenReturn(List.of(
                conf(1, 85.0), conf(2, 88.0), conf(3, 65.0)));

        CalibrationMonitorService.Report rep = monitor.report();

        assertEquals(3, rep.totalResolved());
        assertEquals(0.6667, rep.overallWinRate(), 0.001); // 2 of 3
        CalibrationMonitorService.Bucket b80 = rep.buckets().stream()
                .filter(b -> b.label().equals("80-90%")).findFirst().orElseThrow();
        assertEquals(2, b80.count());
        assertEquals(1, b80.wins());
        assertEquals(0.5, b80.actualWinRate(), 0.001);
        assertNull(b80.modelPredictedWinRate()); // calibrator untrained → null
        CalibrationMonitorService.Bucket b60 = rep.buckets().stream()
                .filter(b -> b.label().equals("60-70%")).findFirst().orElseThrow();
        assertEquals(1.0, b60.actualWinRate(), 0.001);
    }
}
