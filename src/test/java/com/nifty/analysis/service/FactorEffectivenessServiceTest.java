package com.nifty.analysis.service;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactorEffectivenessServiceTest {

    @Mock private TradeResultRepository tradeResultRepository;
    @Mock private SignalExplanationRepository signalExplanationRepository;

    private FactorEffectivenessService service;

    @BeforeEach
    void setUp() {
        service = new FactorEffectivenessService(tradeResultRepository, signalExplanationRepository);
    }

    private TradeResult result(long signalId, double pnl) {
        TradeSignal s = new TradeSignal();
        s.setId(signalId);
        TradeResult r = new TradeResult();
        r.setSignal(s);
        r.setProfitLoss(pnl);
        return r;
    }

    private SignalExplanation exp(long signalId, String factor, double score) {
        TradeSignal s = new TradeSignal();
        s.setId(signalId);
        SignalExplanation e = new SignalExplanation();
        e.setSignal(s);
        e.setFactor(factor);
        e.setScore(score);
        return e;
    }

    @Test
    void emptyData_reportsZeroes() {
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        FactorEffectivenessService.Report rep = service.report();
        assertEquals(0, rep.resolvedTrades());
        assertTrue(rep.factors().isEmpty());
    }

    @Test
    void computesEdgePerFactor_predictiveFactorRanksFirst() {
        // signal 1 won, signal 2 lost.
        when(tradeResultRepository.findAll()).thenReturn(List.of(
                result(1, 500.0), result(2, -300.0)));
        // "Trend" strongly separates (90 on win, 20 on loss → edge 70).
        // "RSI" doesn't separate (50 on both → edge 0).
        when(signalExplanationRepository.findBySignalIdIn(anyList())).thenReturn(List.of(
                exp(1, "Trend", 90.0), exp(1, "RSI", 50.0),
                exp(2, "Trend", 20.0), exp(2, "RSI", 50.0)));

        FactorEffectivenessService.Report rep = service.report();

        assertEquals(2, rep.resolvedTrades());
        assertEquals(1, rep.wins());
        assertEquals(1, rep.losses());
        // Trend (edge 70) ranks ahead of RSI (edge 0).
        assertEquals("Trend", rep.factors().get(0).factor());
        assertEquals(70.0, rep.factors().get(0).edge(), 0.001);
        FactorEffectivenessService.FactorEdge rsi = rep.factors().stream()
                .filter(f -> f.factor().equals("RSI")).findFirst().orElseThrow();
        assertEquals(0.0, rsi.edge(), 0.001);
    }
}
