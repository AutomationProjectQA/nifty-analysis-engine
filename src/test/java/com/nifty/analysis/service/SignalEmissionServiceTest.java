package com.nifty.analysis.service;

import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.LiquidityAgent;
import com.nifty.analysis.agent.RiskAgent;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.instrument.InstrumentSpec;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeLegRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.strategy.StrategyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests the emission internals extracted from DecisionAgent (Phase-3): pricing, guards, persistence. */
@ExtendWith(MockitoExtension.class)
class SignalEmissionServiceTest {

    @Mock private LiquidityAgent liquidityAgent;
    @Mock private RiskAgent riskAgent;
    @Mock private TradeSignalRepository tradeSignalRepository;
    @Mock private TradeLegRepository tradeLegRepository;
    @Mock private SignalExplanationRepository signalExplanationRepository;
    @Mock private TelegramBotService telegramBotService;
    @Mock private OrderExecutionService orderExecutionService;
    @Mock private OptionPricingService optionPricingService;

    @InjectMocks
    private SignalEmissionService service;

    private final InstrumentSpec spec = new InstrumentSpec("NIFTY", 50, 65, true);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "gatingThreshold", 60.0);
        ReflectionTestUtils.setField(service, "targetProfitPercent", 2.0);
        ReflectionTestUtils.setField(service, "stopLossPercent", 40.0);
        ReflectionTestUtils.setField(service, "strikeLadderEnabled", false); // single ATM strike
        ReflectionTestUtils.setField(service, "minLiquidityScore", 70.0);
        ReflectionTestUtils.setField(service, "maxConcurrentPositions", 6);
        ReflectionTestUtils.setField(service, "spreadWidthSteps", 2);
        ReflectionTestUtils.setField(service, "multiLegTargetFraction", 0.6);

        lenient().when(optionPricingService.getOptionLtp(anyString(), anyInt())).thenReturn(150.0);
        lenient().when(orderExecutionService.calculateQuantity(anyDouble(), anyInt(), anyInt())).thenReturn(65);
        lenient().when(orderExecutionService.executeOrder(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(OrderExecutionService.OrderResult.skipped());
        lenient().when(liquidityAgent.evaluateStrike(any(), anyBoolean()))
                .thenReturn(new AgentResponse(100.0, "BULLISH", List.of()));
        lenient().when(riskAgent.evaluateRisk(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new AgentResponse(30.0, "NEUTRAL", List.of("Unfavorable R:R")));
        lenient().when(tradeSignalRepository.findFirstByInstrumentAndStrikeAndSignalTypeAndStatus(
                anyString(), anyInt(), anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(tradeSignalRepository.countByStatus("ACTIVE")).thenReturn(0L);
    }

    private List<OptionSnapshot> atmChain() {
        OptionSnapshot o = new OptionSnapshot();
        o.setStrikePrice(23500);
        o.setCeOi(1_000_000L);
        o.setPeOi(1_000_000L);
        return List.of(o);
    }

    private SignalEmissionService.EmissionResult emitLong(StrategyType strategy, boolean modelReady) {
        ConfidenceEngine.RawConfidenceResult raw =
                new ConfidenceEngine.RawConfidenceResult(60.0, Map.of("Trend", 80.0));
        CriticAgent.CriticResult critic = new CriticAgent.CriticResult(68.0, List.of());
        return service.emit(spec, strategy, 23500, true, "BUY_CE", 23500.0, 68.0,
                /*modelConf*/ 80.0, /*agentConf*/ 60.0, /*rawConf*/ 68.0, modelReady, /*modelWeight*/ 0.4,
                raw, critic, atmChain(), "thesis", /*vix*/ 12.0);
    }

    @Test
    void ladderEmit_placesOrderAndPersistsSignal() {
        SignalEmissionService.EmissionResult r = emitLong(StrategyType.LONG_CALL, true);

        assertFalse(r.multiLeg());
        assertEquals(1, r.emitted());
        verify(tradeSignalRepository, atLeastOnce()).save(any());
        verify(orderExecutionService).executeOrder("BUY_CE", 23500, 23500.0, 1);
    }

    @Test
    void ladderEmit_usesRealLtpQuantityAndPercentLevels() {
        emitLong(StrategyType.LONG_CALL, true);

        ArgumentCaptor<TradeSignal> saved = ArgumentCaptor.forClass(TradeSignal.class);
        verify(tradeSignalRepository, atLeastOnce()).save(saved.capture());
        TradeSignal s = saved.getValue();
        assertEquals(150.0, s.getEntry(), 0.001);     // real LTP
        assertEquals(65, s.getQuantity());            // quantity
        assertEquals(153.0, s.getTarget2(), 0.001);   // +2%
        assertEquals(90.0, s.getStopLoss(), 0.001);   // -40%
    }

    @Test
    void ladderEmit_persistsDecisionProvenance() {
        emitLong(StrategyType.LONG_CALL, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SignalExplanation>> captor = ArgumentCaptor.forClass(List.class);
        verify(signalExplanationRepository).saveAll(captor.capture());
        List<SignalExplanation> saved = captor.getValue();
        assertTrue(saved.stream().anyMatch(e -> "Model_ONNX".equals(e.getFactor()) && e.getScore() == 80.0));
        assertTrue(saved.stream().anyMatch(e -> "Agent_Weighted".equals(e.getFactor()) && e.getScore() == 60.0));
        assertTrue(saved.stream().anyMatch(e -> "Blended_Raw".equals(e.getFactor()) && e.getScore() == 68.0));
        assertTrue(saved.stream().anyMatch(e -> "Final_Confidence".equals(e.getFactor())));
        assertTrue(saved.stream().allMatch(e -> e.getSignal() != null));
    }

    @Test
    void orderFailed_noPhantomSignalCreated() {
        when(orderExecutionService.executeOrder(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(OrderExecutionService.OrderResult.failed());

        SignalEmissionService.EmissionResult r = emitLong(StrategyType.LONG_CALL, true);

        assertEquals(0, r.emitted());
        verify(tradeSignalRepository, never()).save(any());
        verify(signalExplanationRepository, never()).saveAll(any());
    }

    @Test
    void maxConcurrentPositionsReached_noSignal() {
        when(tradeSignalRepository.countByStatus("ACTIVE")).thenReturn(6L); // == cap

        SignalEmissionService.EmissionResult r = emitLong(StrategyType.LONG_CALL, true);

        assertEquals(0, r.emitted());
        verify(tradeSignalRepository, never()).save(any());
        verify(orderExecutionService, never()).executeOrder(anyString(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void multiLeg_emitsIronCondorWithLegs() {
        SignalEmissionService.EmissionResult r = emitLong(StrategyType.IRON_CONDOR, true);

        assertTrue(r.multiLeg());
        assertEquals(1, r.emitted());
        ArgumentCaptor<TradeSignal> saved = ArgumentCaptor.forClass(TradeSignal.class);
        verify(tradeSignalRepository, atLeastOnce()).save(saved.capture());
        assertEquals("IRON_CONDOR", saved.getValue().getStrategy());
        verify(tradeLegRepository).saveAll(any());
    }
}
