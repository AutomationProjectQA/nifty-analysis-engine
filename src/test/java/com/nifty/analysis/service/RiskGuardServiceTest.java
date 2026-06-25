package com.nifty.analysis.service;

import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskGuardServiceTest {

    @Mock private TradeSignalRepository tradeSignalRepository;
    @Mock private TradeResultRepository tradeResultRepository;
    @Mock private DataFeedStatus dataFeedStatus;

    @InjectMocks
    private RiskGuardService riskGuardService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(riskGuardService, "tradingEnabled", true);
        ReflectionTestUtils.setField(riskGuardService, "maxTradesPerDay", 5);
        ReflectionTestUtils.setField(riskGuardService, "maxLossPerDay", 1000.0);
        ReflectionTestUtils.setField(riskGuardService, "provider", "angelone");
        ReflectionTestUtils.setField(riskGuardService, "blockOnSimulatedData", true);
        // Live data by default so the existing limit tests behave normally.
        lenient().when(dataFeedStatus.isLive()).thenReturn(true);
    }

    @Test
    void simulatedData_blocksNewTrade() {
        when(dataFeedStatus.isLive()).thenReturn(false);
        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();
        assertFalse(check.allowed());
        assertTrue(check.reason().toLowerCase().contains("simulated"));
    }

    @Test
    void liveData_doesNotBlockOnDataSource() {
        when(dataFeedStatus.isLive()).thenReturn(true);
        when(tradeSignalRepository.findBySignalTimeAfter(any())).thenReturn(new ArrayList<>());
        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();
        assertTrue(check.allowed());
    }

    @Test
    void simulatedProvider_neverBlocksOnDataSource() {
        // Pure simulated/demo mode is intentional: the provider check short-circuits
        // before isLive() is consulted, so a degraded feed must not block here.
        ReflectionTestUtils.setField(riskGuardService, "provider", "simulated");
        when(tradeSignalRepository.findBySignalTimeAfter(any())).thenReturn(new ArrayList<>());
        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();
        assertTrue(check.allowed());
    }

    private TradeSignal signal(long id) {
        TradeSignal s = new TradeSignal();
        s.setId(id);
        s.setSignalTime(LocalDateTime.now());
        return s;
    }

    @Test
    void killSwitchOff_blocks() {
        ReflectionTestUtils.setField(riskGuardService, "tradingEnabled", false);

        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();

        assertFalse(check.allowed());
    }

    @Test
    void maxTradesReached_blocks() {
        List<TradeSignal> today = new ArrayList<>();
        for (long i = 1; i <= 5; i++) {
            today.add(signal(i));
        }
        when(tradeSignalRepository.findBySignalTimeAfter(any())).thenReturn(today);
        // results don't matter here; loss check isn't reached
        lenient().when(tradeResultRepository.findBySignalId(any())).thenReturn(Optional.empty());

        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();

        assertFalse(check.allowed());
    }

    @Test
    void dailyLossLimitExceeded_blocks() {
        TradeSignal s1 = signal(1);
        TradeSignal s2 = signal(2);
        when(tradeSignalRepository.findBySignalTimeAfter(any())).thenReturn(List.of(s1, s2));

        TradeResult r1 = new TradeResult();
        r1.setProfitLoss(-600.0);
        TradeResult r2 = new TradeResult();
        r2.setProfitLoss(-600.0); // cumulative -1200 <= -1000 limit
        when(tradeResultRepository.findBySignalId(1L)).thenReturn(Optional.of(r1));
        when(tradeResultRepository.findBySignalId(2L)).thenReturn(Optional.of(r2));

        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();

        assertFalse(check.allowed());
    }

    @Test
    void withinAllLimits_allows() {
        TradeSignal s1 = signal(1);
        when(tradeSignalRepository.findBySignalTimeAfter(any())).thenReturn(List.of(s1));

        TradeResult r1 = new TradeResult();
        r1.setProfitLoss(-200.0); // small loss, under limit
        when(tradeResultRepository.findBySignalId(1L)).thenReturn(Optional.of(r1));

        RiskGuardService.RiskCheck check = riskGuardService.canOpenNewTrade();

        assertTrue(check.allowed());
    }
}
