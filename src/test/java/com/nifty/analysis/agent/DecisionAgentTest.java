package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.LlmService;
import com.nifty.analysis.service.OnnxModelService;
import com.nifty.analysis.service.RiskGuardService;
import com.nifty.analysis.service.SignalEmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.verification.VerificationMode;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the decision-GATING logic: the early-return gates, the ONNX/agent confidence blend, the
 * cold-start fallback, and the gating threshold. Signal EMISSION is delegated to (and verified
 * against) {@link SignalEmissionService}; its internals are tested in SignalEmissionServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class DecisionAgentTest {

    @Mock private ConfidenceEngine confidenceEngine;
    @Mock private CriticAgent criticAgent;
    @Mock private TechnicalAgent technicalAgent;
    @Mock private OptionsAgent optionsAgent;
    @Mock private SentimentAgent sentimentAgent;
    @Mock private MarketAgent marketAgent;
    @Mock private EntryTimingAgent entryTimingAgent;
    @Mock private MarketRegimeAgent marketRegimeAgent;
    @Mock private MultiTimeframeAgent multiTimeframeAgent;
    @Mock private com.nifty.analysis.engine.ConfidenceCalibrator calibrator;
    @Mock private com.nifty.analysis.instrument.InstrumentRegistry instrumentRegistry;
    @Mock private com.nifty.analysis.strategy.RegimeStrategySelector strategySelector;
    @Mock private MarketSnapshotRepository marketSnapshotRepository;
    @Mock private OptionSnapshotRepository optionSnapshotRepository;
    @Mock private com.nifty.analysis.repository.DecisionTraceRepository decisionTraceRepository;
    @Mock private LlmService llmService;
    @Mock private OnnxModelService onnxModelService;
    @Mock private RiskGuardService riskGuardService;
    @Mock private SignalEmissionService signalEmissionService;
    // Real policy (not a mock) so getters return the values we set; @InjectMocks injects the spy.
    @org.mockito.Spy private com.nifty.analysis.config.TradingPolicy tradingPolicy = new com.nifty.analysis.config.TradingPolicy();

    @InjectMocks
    private DecisionAgent decisionAgent;

    private MarketSnapshot latest;
    private final LocalDateTime now = LocalDateTime.of(2026, 6, 25, 11, 0);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tradingPolicy, "gatingThreshold", 60.0);
        ReflectionTestUtils.setField(decisionAgent, "modelWeight", 0.4);
        ReflectionTestUtils.setField(decisionAgent, "modelMinHistory", 50L);
        ReflectionTestUtils.setField(decisionAgent, "sidewaysExtraGate", 8.0);
        ReflectionTestUtils.setField(decisionAgent, "calibrationMaxRequiredWinRate", 0.65);
        ReflectionTestUtils.setField(decisionAgent, "momentumOppositionPenalty", 12.0);
        ReflectionTestUtils.setField(decisionAgent, "momentumConfirmationEnabled", false);
        ReflectionTestUtils.setField(decisionAgent, "entryTimingEnabled", false);
        ReflectionTestUtils.setField(decisionAgent, "sessionFilterEnabled", false);
        ReflectionTestUtils.setField(decisionAgent, "maxOptionStalenessSeconds", 0L); // disable freshness gate in tests

        lenient().when(instrumentRegistry.get("NIFTY"))
                .thenReturn(new com.nifty.analysis.instrument.InstrumentSpec("NIFTY", 50, 65, true));
        lenient().when(strategySelector.select(anyString(), anyBoolean()))
                .thenReturn(com.nifty.analysis.strategy.StrategyType.LONG_CALL);
        lenient().when(riskGuardService.canOpenNewTrade(anyString()))
                .thenReturn(RiskGuardService.RiskCheck.allow());
        // Emission delegated to the service — default: one signal emitted. Block tests verify it's NOT called.
        stubEmitReturns(false, 1, 1);

        latest = new MarketSnapshot();
        latest.setSnapshotTime(now);
        latest.setNiftySpot(23500.0);
        latest.setNiftyFuture(23530.0);
        latest.setIndiaVix(12.0);
    }

    // ---- helpers ----

    /** Stubs SignalEmissionService.emit(...) to return the given result. */
    private void stubEmitReturns(boolean multiLeg, int emitted, int candidates) {
        lenient().when(signalEmissionService.emit(any(), any(), anyInt(), anyBoolean(), anyString(), anyDouble(),
                        anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), anyDouble(),
                        any(), any(), anyList(), any(), anyDouble()))
                .thenReturn(new SignalEmissionService.EmissionResult(multiLeg, emitted, candidates));
    }

    /** Verifies whether emit(...) was invoked with the given verification mode. */
    private void verifyEmit(VerificationMode mode) {
        verify(signalEmissionService, mode).emit(any(), any(), anyInt(), anyBoolean(), anyString(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), anyDouble(),
                any(), any(), anyList(), any(), anyDouble());
    }

    private List<OptionSnapshot> atmChain() {
        OptionSnapshot o = new OptionSnapshot();
        o.setStrikePrice(23500);
        o.setCeOi(1_000_000L);
        o.setPeOi(1_000_000L);
        return List.of(o);
    }

    /** Stubs the path up to (but not including) the confidence computation, for a BULLISH setup. */
    private void stubUpToConfidence() {
        when(optionSnapshotRepository.findLatestSnapshotTimeByInstrument("NIFTY")).thenReturn(now);
        when(optionSnapshotRepository.findByInstrumentAndSnapshotTime("NIFTY", now)).thenReturn(atmChain());
        when(marketRegimeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(85.0, "TRENDING_BULLISH", List.of()));
        when(technicalAgent.analyze(latest)).thenReturn(new AgentResponse(80.0, "BULLISH", List.of()));
        when(technicalAgent.getFeatures(latest))
                .thenReturn(new TechnicalAgent.TechnicalFeatures(50, 1.0, 1.0, 12, 0.0, 0.01, 0.0, 1.0));
        when(onnxModelService.predictBullishProbability(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(80.0);
    }

    private void stubAgentConfidence(double agentConfidence) {
        when(confidenceEngine.calculateRawConfidence(eq(latest), anyList(), anyDouble(), eq(true)))
                .thenReturn(new ConfidenceEngine.RawConfidenceResult(agentConfidence, Map.of("Trend", agentConfidence)));
    }

    private void stubConfidenceWithFactors(double agentConfidence, Map<String, Double> factors) {
        when(confidenceEngine.calculateRawConfidence(eq(latest), anyList(), anyDouble(), eq(true)))
                .thenReturn(new ConfidenceEngine.RawConfidenceResult(agentConfidence, factors));
    }

    private void stubCritic(double adjustedConfidence) {
        when(criticAgent.evaluateAndApplyPenalties(anyDouble(), eq(latest), anyList(), eq(true)))
                .thenReturn(new CriticAgent.CriticResult(adjustedConfidence, List.of()));
    }

    // ---- gate tests ----

    @Test
    void noOptionData_skipsEvaluation() {
        when(optionSnapshotRepository.findLatestSnapshotTimeByInstrument("NIFTY")).thenReturn(null);

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never());
    }

    @Test
    void riskGuardBlocks_skipsEvaluationEntirely() {
        when(riskGuardService.canOpenNewTrade(anyString()))
                .thenReturn(RiskGuardService.RiskCheck.deny("Max trades per day reached (5/5)."));

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verify(optionSnapshotRepository, never()).findLatestSnapshotTimeByInstrument(anyString());
        verifyEmit(never());

        // Phase-0 observability: the rejection is recorded as a decision trace at the risk_guard gate.
        ArgumentCaptor<com.nifty.analysis.entity.DecisionTrace> traceCaptor =
                ArgumentCaptor.forClass(com.nifty.analysis.entity.DecisionTrace.class);
        verify(decisionTraceRepository).save(traceCaptor.capture());
        com.nifty.analysis.entity.DecisionTrace t = traceCaptor.getValue();
        assertEquals("REJECTED", t.getOutcome());
        assertEquals("risk_guard", t.getRejectStage());
        assertTrue(t.getRejectReason().contains("Max trades per day"));
    }

    @Test
    void sidewaysRegime_raisesGateInsteadOfSkipping() {
        // Sideways no longer hard-skips: it proceeds but demands gating + sidewaysExtraGate (60 + 8 = 68).
        stubUpToConfidence();
        when(marketRegimeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(50.0, "SIDEWAYS", List.of()));
        when(onnxModelService.isModelLoaded()).thenReturn(false);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(63.0);
        stubCritic(63.0); // clears the base 60 gate but NOT the stricter 68 sideways gate

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verify(technicalAgent).analyze(latest); // direction WAS computed (not skipped)
        verifyEmit(never());                     // but the stricter sideways gate blocked emission
    }

    @Test
    void neutralBias_skipsEvaluation() {
        when(optionSnapshotRepository.findLatestSnapshotTimeByInstrument("NIFTY")).thenReturn(now);
        when(optionSnapshotRepository.findByInstrumentAndSnapshotTime("NIFTY", now)).thenReturn(List.of());
        when(marketRegimeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(85.0, "TRENDING_BULLISH", List.of()));
        when(technicalAgent.analyze(latest)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never());
    }

    @Test
    void confidenceBelowThreshold_noSignalGenerated() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(50.0);
        stubCritic(59.0); // below 60 threshold

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never());
        verify(llmService, never()).generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString());
    }

    @Test
    void modelReady_blendsModelAndAgentConfidence_andEmits() {
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
        verifyEmit(times(1));
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

    // --- P3-1: minimum-confirmation gate ---

    @Test
    void minConfirmation_blocksWhenOnlyTrendConfirms() {
        // High blended score driven by trend alone (OI/Futures/PCR absent → neutral 50, not confirming) → no trade.
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubConfidenceWithFactors(80.0, Map.of("Trend", 100.0, "MultiTimeframe", 100.0, "VWAP", 100.0));
        stubCritic(80.0);
        ReflectionTestUtils.setField(decisionAgent, "minConfirmationEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "confirmScore", 60.0);
        ReflectionTestUtils.setField(decisionAgent, "notOpposingScore", 50.0);

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never()); // blocked: no order flow confirmation
    }

    @Test
    void minConfirmation_passesWithTrendFlowAndNonOpposingPcr() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubConfidenceWithFactors(80.0, Map.of("Trend", 100.0, "OI", 100.0, "PCR", 100.0));
        stubCritic(80.0);
        ReflectionTestUtils.setField(decisionAgent, "minConfirmationEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "confirmScore", 60.0);
        ReflectionTestUtils.setField(decisionAgent, "notOpposingScore", 50.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(times(1));
    }

    // --- P3-3: direction by consensus ---

    @Test
    void directionConsensus_skipsWhenNoMajority() {
        // Only technical is bullish (1 of 4) → below the 3-vote requirement → skip.
        when(optionSnapshotRepository.findLatestSnapshotTimeByInstrument("NIFTY")).thenReturn(now);
        when(optionSnapshotRepository.findByInstrumentAndSnapshotTime("NIFTY", now)).thenReturn(atmChain());
        when(marketRegimeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(85.0, "TRENDING_BULLISH", List.of()));
        when(technicalAgent.analyze(latest)).thenReturn(new AgentResponse(80.0, "BULLISH", List.of()));
        when(multiTimeframeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        when(optionsAgent.analyze(anyList(), anyDouble(), anyDouble()))
                .thenReturn(new AgentResponse(50.0, "NEUTRAL", List.of()));
        latest.setNiftyFuture(23505.0); // premium +5 → no futures vote
        ReflectionTestUtils.setField(decisionAgent, "directionConsensusEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "minDirectionAgreement", 3);

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never());
    }

    @Test
    void directionConsensus_buysCeOnBullishMajority() {
        // technical + mtf + futures(+30) + OI all bullish → 4 votes → trade.
        stubUpToConfidence();
        when(multiTimeframeAgent.analyze("NIFTY", now)).thenReturn(new AgentResponse(90.0, "BULLISH", List.of()));
        when(optionsAgent.analyze(anyList(), anyDouble(), anyDouble()))
                .thenReturn(new AgentResponse(85.0, "BULLISH", List.of()));
        latest.setNiftyFuture(23530.0); // premium +30 → bullish futures vote
        ReflectionTestUtils.setField(decisionAgent, "directionConsensusEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "minDirectionAgreement", 3);
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(80.0);
        stubCritic(80.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(times(1));
    }

    // --- P4-1: calibrated-probability gate ---

    @Test
    void calibration_blocksWhenProbabilityBelowBreakEven() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(80.0);
        stubCritic(80.0);
        ReflectionTestUtils.setField(decisionAgent, "calibrationEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "calibrationMargin", 0.0);
        when(calibrator.isTrained()).thenReturn(true);
        when(calibrator.breakEvenWinRate()).thenReturn(0.55);
        when(calibrator.probabilityOfWin(80.0)).thenReturn(0.40); // < 0.55 → block

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never());
    }

    @Test
    void calibration_allowsWhenProbabilityClearsBreakEven() {
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(80.0);
        stubCritic(80.0);
        ReflectionTestUtils.setField(decisionAgent, "calibrationEnabled", true);
        when(calibrator.isTrained()).thenReturn(true);
        when(calibrator.breakEvenWinRate()).thenReturn(0.55);
        when(calibrator.probabilityOfWin(80.0)).thenReturn(0.70); // clears
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(times(1));
    }

    // --- #206: ML candidate re-scoring (rescue) ---

    @Test
    void mlRescue_recoversCandidateBelowGateWhenProbabilityHigh() {
        // Confidence (50) is below the gate (60), but the ML model is strongly bullish (80% >= 75%)
        // → rescued past the scoring gate and emitted (no other safety gate blocks here).
        stubUpToConfidence(); // ML predictBullishProbability stubbed to 80
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(50.0);
        stubCritic(50.0); // final 50 < gate 60
        ReflectionTestUtils.setField(decisionAgent, "mlRescueEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "mlRescueMinProbability", 0.75);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(times(1));
    }

    @Test
    void mlRescue_doesNotRecoverWhenProbabilityTooLow() {
        // Confidence below gate AND ML probability (60%) below the rescue bar (75%) → still rejected.
        stubUpToConfidence();
        when(onnxModelService.predictBullishProbability(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(60.0); // ML only mildly bullish
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(50.0);
        stubCritic(50.0);
        ReflectionTestUtils.setField(decisionAgent, "mlRescueEnabled", true);
        ReflectionTestUtils.setField(decisionAgent, "mlRescueMinProbability", 0.75);

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        verifyEmit(never());
    }

    @Test
    void perStrikeFiltersBlockAll_tracedAsRejected() {
        // Gates pass but emission returns 0 (every strike filtered) → trace REJECTED at per_strike_filters.
        stubUpToConfidence();
        when(onnxModelService.isModelLoaded()).thenReturn(true);
        when(marketSnapshotRepository.count()).thenReturn(100L);
        stubAgentConfidence(80.0);
        stubCritic(80.0);
        when(llmService.generateTradeExplanation(anyString(), anyInt(), anyDouble(), anyMap(), anyString()))
                .thenReturn("thesis");
        stubEmitReturns(false, 0, 3); // emission filtered every candidate strike

        decisionAgent.evaluateMarketForSignals(latest, 23490.0);

        ArgumentCaptor<com.nifty.analysis.entity.DecisionTrace> cap =
                ArgumentCaptor.forClass(com.nifty.analysis.entity.DecisionTrace.class);
        verify(decisionTraceRepository).save(cap.capture());
        assertEquals("REJECTED", cap.getValue().getOutcome());
        assertEquals("per_strike_filters", cap.getValue().getRejectStage());
    }
}
