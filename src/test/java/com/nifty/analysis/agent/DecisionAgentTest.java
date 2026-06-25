package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.service.LlmService;
import com.nifty.analysis.service.OnnxModelService;
import com.nifty.analysis.service.OptionPricingService;
import com.nifty.analysis.service.OrderExecutionService;
import com.nifty.analysis.service.RiskGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the decision-gating logic: the early-return gates, the ONNX/agent
 * confidence blend, the cold-start fallback, and the gating threshold.
 */
@ExtendWith(MockitoExtension.class)
class DecisionAgentTest {

    @Mock private ConfidenceEngine confidenceEngine;
    @Mock private CriticAgent criticAgent;
    @Mock private TechnicalAgent technicalAgent;
    @Mock private OptionsAgent optionsAgent;
    @Mock private SentimentAgent sentimentAgent;
    @Mock private MarketAgent marketAgent;
    @Mock private LiquidityAgent liquidityAgent;
    @Mock private EntryTimingAgent entryTimingAgent;
    @Mock private RiskAgent riskAgent;
    @Mock private MarketRegimeAgent marketRegimeAgent;
    @Mock private MarketSnapshotRepository marketSnapshotRepository;
    @Mock private TradeSignalRepository tradeSignalRepository;
    @Mock private SignalExplanationRepository signalExplanationRepository;
    @Mock private OptionSnapshotRepository optionSnapshotRepository;
    @Mock private TelegramBotService telegramBotService;
    @Mock private LlmService llmService;
    @Mock private OrderExecutionService orderExecutionService;
    @Mock private OnnxModelService onnxModelService;
    @Mock private RiskGuardService riskGuardService;
    @Mock private OptionPricingService optionPricingService;

    @InjectMocks
    private DecisionAgent decisionAgent;

    private MarketSnapshot latest;
    private final LocalDateTime now = LocalDateTime.of(2026, 6, 25, 11, 0);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(decisionAgent, "gatingThreshold", 60.0);
        ReflectionTestUtils.setField(decisionAgent, "modelWeight", 0.4);
        ReflectionTestUtils.setField(decisionAgent, "modelMinHistory", 50L);
        ReflectionTestUtils.setField(decisionAgent, "targetProfitPercent", 2.0);
        ReflectionTestUtils.setField(decisionAgent, "stopLossPercent", 40.0);
        // Signal-generation tuning fields: keep single-strike + extra gates deterministic.
        ReflectionTestUtils.setField(decisionAgent, "strikeLadderEnabled", false);
        ReflectionTestUtils.setField(decisionAgent, "strikeStep", 50);
        ReflectionTestUtils.setField(decisionAgent, "sidewaysExtraGate", 8.0);
        ReflectionTestUtils.setField(decisionAgent, "minLiquidityScore", 70.0);
        ReflectionTestUtils.setField(decisionAgent, "momentumConfirmationEnabled", false);
        ReflectionTestUtils.setField(decisionAgent, "entryTimingEnabled", false);
        ReflectionTestUtils.setField(decisionAgent, "sessionFilterEnabled", false);

        // Risk guard allows trading by default; one test overrides this.
        lenient().when(riskGuardService.canOpenNewTrade())
                .thenReturn(RiskGuardService.RiskCheck.allow());
        // Pricing stubs used only by the signal-generating tests.
        lenient().when(optionPricingService.getOptionLtp(anyString(), anyInt())).thenReturn(150.0);
        lenient().when(orderExecutionService.calculateQuantity(anyDouble())).thenReturn(65);
        // Strikes are liquid by default; the per-strike liquidity gate passes.
        lenient().when(liquidityAgent.evaluateStrike(any(), anyBoolean()))
                .thenReturn(new AgentResponse(100.0, "BULLISH", List.of()));
        // Risk advisory stub (advisory only — does not block signals).
        lenient().when(riskAgent.evaluateRisk(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new AgentResponse(30.0, "NEUTRAL", List.of("Unfavorable Risk/Reward ratio (1:0.03).")));

        latest = new MarketSnapshot();
        latest.setSnapshotTime(now);
        latest.setNiftySpot(23500.0);
        latest.setNiftyFuture(23530.0);
        latest.setIndiaVix(12.0);
    }

    /** A single-strike (ATM 23500) option chain so the per-strike liquidity lookup resolves. */
    private List<OptionSnapshot> atmChain() {
        OptionSnapshot o = new OptionSnapshot();
        o.setStrikePrice(23500);
        o.setCeOi(1_000_000L);
        o.setPeOi(1_000_000L);
        return List.of(o);
    }

    /** Stubs the path up to (but not including) the confidence computation, for a BULLISH setup. */
    private void stubUpToConfidence() {
        when(optionSnapshotRepository.findLatestSnapshotTime()).thenReturn(now);
        when(optionSnapshotRepository.findBySnapshotTime(now)).thenReturn(atmChain());
        when(marketRegimeAgent.analyze(now)).thenReturn(new AgentResponse(85.0, "TRENDING_BULLISH", List.of()));
        when(technicalAgent.analyze(latest)).thenReturn(new AgentResponse(80.0, "BULLISH", List.of()));
        // Reached only when a signal is actually emitted (per-strike duplicate guard).
        lenient().when(tradeSignalRepository.findFirstByStrikeAndSignalTypeAndStatus(23500, "BUY_CE", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(technicalAgent.getFeatures(latest))
                .thenReturn(new TechnicalAgent.TechnicalFeatures(50, 1.0, 1.0, 12, 0.0, 0.01, 0.0, 1.0));
        when(onnxModelService.predictBullishProbability(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(80.0);
    }

    private void stubAgentConfidence(double agentConfidence) {
        when(confidenceEngine.calculateRawConfidence(eq(latest), anyList(), anyDouble(), eq(true)))
                .thenReturn(new ConfidenceEngine.RawConfidenceResult(agentConfidence, Map.of("Trend", agentConfidence)));
    }

    private void stubCritic(double adjustedConfidence) {
        when(criticAgent.evaluateAndApplyPenalties(anyDouble(), eq(latest), anyList(), eq(true)))
                .thenReturn(new CriticAgent.CriticResult(adjustedConfidence, List.of()));
    }

    @Test
    void noOptionData_skipsEvaluation() {
        when(optionSnapshotRepository.findLatestSnapshotTime()).thenReturn(null);

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verify(tradeSignalRepository, never()).save(any());
        verify(orderExecutionService, never()).executeOrder(anyString(), anyInt(), anyDouble());
    }

    @Test
    void riskGuardBlocks_skipsEvaluationEntirely() {
        when(riskGuardService.canOpenNewTrade())
                .thenReturn(RiskGuardService.RiskCheck.deny("Max trades per day reached (5/5)."));

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        // Blocked before any market data is even fetched
        verify(optionSnapshotRepository, never()).findLatestSnapshotTime();
        verify(tradeSignalRepository, never()).save(any());
        verify(orderExecutionService, never()).executeOrder(anyString(), anyInt(), anyDouble());
    }

    @Test
    void sidewaysRegime_raisesGateInsteadOfSkipping() {
        // Sideways no longer hard-skips: it proceeds but demands gating + sidewaysExtraGate (60 + 8 = 68).
        stubUpToConfidence();
        when(marketRegimeAgent.analyze(now)).thenReturn(new AgentResponse(50.0, "SIDEWAYS", List.of()));
        when(onnxModelService.isModelLoaded()).thenReturn(false);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(63.0);
        stubCritic(63.0); // clears the base 60 gate but NOT the stricter 68 sideways gate

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        // Evaluation is no longer skipped (direction was computed)...
        verify(technicalAgent).analyze(latest);
        // ...but the stricter sideways gate blocks the trade.
        verify(tradeSignalRepository, never()).save(any());
    }

    @Test
    void neutralBias_skipsEvaluation() {
        when(optionSnapshotRepository.findLatestSnapshotTime()).thenReturn(now);
        when(optionSnapshotRepository.findBySnapshotTime(now)).thenReturn(List.of());
        when(marketRegimeAgent.analyze(now)).thenReturn(new AgentResponse(85.0, "TRENDING_BULLISH", List.of()));
        when(technicalAgent.analyze(latest)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verify(tradeSignalRepository, never()).save(any());
        verify(orderExecutionService, never()).executeOrder(anyString(), anyInt(), anyDouble());
    }

    @Test
    void confidenceBelowThreshold_noSignalGenerated() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(50.0);
        stubCritic(59.0); // below 60 threshold

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verify(tradeSignalRepository, never()).save(any());
        verify(orderExecutionService, never()).executeOrder(anyString(), anyInt(), anyDouble());
        verify(llmService, never()).generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString());
    }

    @Test
    void modelReady_blendsModelAndAgentConfidence_andGeneratesSignal() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(60.0);
        stubCritic(68.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        // Blend: 0.4 * 80 (model) + 0.6 * 60 (agent) = 68.0 fed to the critic
        ArgumentCaptor<Double> rawConfidence = ArgumentCaptor.forClass(Double.class);
        verify(criticAgent).evaluateAndApplyPenalties(rawConfidence.capture(), eq(latest), anyList(), eq(true));
        assertEquals(68.0, rawConfidence.getValue(), 0.001);

        verify(tradeSignalRepository, atLeastOnce()).save(any());
        verify(orderExecutionService).executeOrder("BUY_CE", 23500, 23500.0);
    }

    @Test
    void generatedSignal_usesRealLtpQuantityAndPercentLevels() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(60.0);
        stubCritic(68.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        ArgumentCaptor<TradeSignal> saved = ArgumentCaptor.forClass(TradeSignal.class);
        verify(tradeSignalRepository, atLeastOnce()).save(saved.capture());
        TradeSignal signal = saved.getValue();
        assertEquals(150.0, signal.getEntry(), 0.001);        // real LTP (stubbed)
        assertEquals(65, signal.getQuantity());               // quantity (stubbed)
        assertEquals(153.0, signal.getTarget2(), 0.001);      // +2% target
        assertEquals(90.0, signal.getStopLoss(), 0.001);      // -40% stop
    }

    @Test
    void generatedSignal_persistsDecisionProvenance() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(60.0);
        stubCritic(68.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SignalExplanation>> captor = ArgumentCaptor.forClass(List.class);
        verify(signalExplanationRepository).saveAll(captor.capture());
        List<SignalExplanation> saved = captor.getValue();

        assertTrue(saved.stream().anyMatch(e -> "Model_ONNX".equals(e.getFactor()) && e.getScore() == 80.0));
        assertTrue(saved.stream().anyMatch(e -> "Agent_Weighted".equals(e.getFactor()) && e.getScore() == 60.0));
        assertTrue(saved.stream().anyMatch(e -> "Blended_Raw".equals(e.getFactor()) && e.getScore() == 68.0));
        assertTrue(saved.stream().anyMatch(e -> "Final_Confidence".equals(e.getFactor())));
        // every provenance row must be linked to the signal
        assertTrue(saved.stream().allMatch(e -> e.getSignal() != null));
    }

    @Test
    void modelNotLoaded_fallsBackToAgentConfidenceOnly() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(false);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(60.0);
        stubCritic(60.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        // Cold-start: model output (80) is IGNORED; raw confidence == agent score (60), not the blend (68)
        ArgumentCaptor<Double> rawConfidence = ArgumentCaptor.forClass(Double.class);
        verify(criticAgent).evaluateAndApplyPenalties(rawConfidence.capture(), eq(latest), anyList(), eq(true));
        assertEquals(60.0, rawConfidence.getValue(), 0.001);
    }
}
