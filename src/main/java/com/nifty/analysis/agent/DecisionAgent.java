package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.service.LlmService;
import com.nifty.analysis.service.OnnxModelService;
import com.nifty.analysis.service.OptionPricingService;
import com.nifty.analysis.service.OrderExecutionService;
import com.nifty.analysis.service.RiskGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionAgent {

    @Value("${nifty.gating-threshold:80.0}")
    private double gatingThreshold;

    // Weight given to the ONNX model when blending with the rule-based agent score
    // (0.0 = ignore the model entirely, 1.0 = use the model only).
    @Value("${nifty.model-weight:0.4}")
    private double modelWeight;

    // Minimum number of stored market snapshots before the ONNX model is trusted.
    // Below this, the model's features are mostly cold-start fallbacks, so we rely
    // on the rule-based agent score instead.
    @Value("${nifty.model-min-history:50}")
    private long modelMinHistory;

    // --- Signal generation tuning (more trades + higher accuracy) ---
    @Value("${nifty.signal.sideways-extra-gate:8.0}")
    private double sidewaysExtraGate;

    @Value("${nifty.signal.momentum-confirmation-enabled:true}")
    private boolean momentumConfirmationEnabled;

    @Value("${nifty.signal.entry-timing-enabled:true}")
    private boolean entryTimingEnabled;

    @Value("${nifty.timing.session-filter-enabled:true}")
    private boolean sessionFilterEnabled;

    // Phase-2 DB-P16-3: reject when the latest option chain is older than this vs the market tick —
    // never size/emit a trade against stale OI/IV/LTP. 0 disables the check.
    @Value("${nifty.signal.max-option-staleness-seconds:300}")
    private long maxOptionStalenessSeconds;

    // Soften (don't skip) the volatile open/close window: raise the confidence gate by this many
    // points instead of blocking outright, so a strong open-driven move can still trade.
    @Value("${nifty.timing.volatile-window-gate-penalty:10.0}")
    private double volatileWindowGatePenalty;

    // Soften (don't skip) an over-extended-above-VWAP entry: subtract this confidence penalty
    // instead of blocking, so chasing setups need extra conviction rather than being banned.
    @Value("${nifty.signal.entry-overextension-penalty:15.0}")
    private double entryOverextensionPenalty;

    // Soften (don't skip) an opposing 1-min momentum read: subtract this confidence penalty
    // instead of vetoing the multi-signal consensus.
    @Value("${nifty.signal.momentum-opposition-penalty:12.0}")
    private double momentumOppositionPenalty;

    // --- P3-1: minimum-confirmation gate (evidence-based, not one collinear factor) ---
    @Value("${nifty.signal.min-confirmation-enabled:true}")
    private boolean minConfirmationEnabled;
    @Value("${nifty.signal.confirm-score:60.0}")
    private double confirmScore;       // a factor "confirms" when its direction-aware score >= this
    @Value("${nifty.signal.not-opposing-score:50.0}")
    private double notOpposingScore;   // a factor is "not opposing" when its score >= this (>= neutral)

    // --- P3-3: direction by consensus of independent signals (not one 1-min tick) ---
    @Value("${nifty.signal.direction-consensus-enabled:true}")
    private boolean directionConsensusEnabled;
    @Value("${nifty.signal.min-direction-agreement:2}")
    private int minDirectionAgreement; // of 4 votes: technical, multi-timeframe, futures, OI

    // --- P4-1: gate on calibrated probability of winning, not raw confidence points ---
    @Value("${nifty.calibration.enabled:true}")
    private boolean calibrationEnabled;
    @Value("${nifty.calibration.margin:0.0}")
    private double calibrationMargin; // extra cushion above break-even win-rate (0..1)
    // Cap the required win-rate the calibration gate can demand. The break-even from the owner's
    // 2%/20% R:R is ~0.91, which is unattainable and blocks every trade once trained. Until the
    // R:R is revisited, cap the bar so calibration tightens but never fully closes the engine.
    // (Phase-1 mitigation DA-F5 — flag for owner; does NOT change the actual target/stop.)
    @Value("${nifty.calibration.max-required-winrate:0.65}")
    private double calibrationMaxRequiredWinRate;

    // Cached once enough snapshot history exists, to avoid counting a growing table each cycle.
    private volatile boolean historySufficient = false;

    private final ConfidenceEngine confidenceEngine;
    private final CriticAgent criticAgent;
    private final TechnicalAgent technicalAgent;
    private final OptionsAgent optionsAgent;
    private final SentimentAgent sentimentAgent;
    private final MarketAgent marketAgent;
    private final EntryTimingAgent entryTimingAgent;

    private final MarketRegimeAgent marketRegimeAgent;
    private final MultiTimeframeAgent multiTimeframeAgent;
    private final com.nifty.analysis.engine.ConfidenceCalibrator calibrator;
    private final com.nifty.analysis.instrument.InstrumentRegistry instrumentRegistry;
    private final com.nifty.analysis.strategy.RegimeStrategySelector strategySelector;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final com.nifty.analysis.repository.DecisionTraceRepository decisionTraceRepository;
    private final LlmService llmService;
    private final OnnxModelService onnxModelService;
    private final RiskGuardService riskGuardService;
    // Phase-3: emission (build/price/execute/persist/notify) is delegated to this service.
    private final com.nifty.analysis.service.SignalEmissionService signalEmissionService;

    /** Phase-0 observability accumulator for one evaluation pass (per-gate notes + outcome). */
    private static final class TraceCtx {
        final String cycleId;
        final String instrument;
        final LocalDateTime evaluationTime;
        String direction;
        Double finalConfidence;
        Double effectiveGate;
        final StringBuilder gates = new StringBuilder();
        TraceCtx(String cycleId, String instrument, LocalDateTime t) {
            this.cycleId = cycleId; this.instrument = instrument; this.evaluationTime = t;
        }
        void note(String gate, String detail) { gates.append(gate).append(": ").append(detail).append('\n'); }
    }

    /** Persists a decision trace. Best-effort: a trace failure must never break the decision path. */
    private void saveTrace(TraceCtx ctx, String outcome, String rejectStage, String rejectReason, int emitted) {
        try {
            com.nifty.analysis.entity.DecisionTrace t = new com.nifty.analysis.entity.DecisionTrace();
            t.setCycleId(ctx.cycleId);
            t.setInstrument(ctx.instrument);
            t.setEvaluationTime(ctx.evaluationTime);
            t.setDirection(ctx.direction);
            t.setFinalConfidence(ctx.finalConfidence);
            t.setEffectiveGate(ctx.effectiveGate);
            t.setOutcome(outcome);
            t.setRejectStage(rejectStage);
            t.setRejectReason(rejectReason);
            t.setGateDetail(ctx.gates.toString());
            t.setSignalsEmitted(emitted);
            t.setCreatedAt(com.nifty.analysis.util.TimeUtil.nowIst());
            decisionTraceRepository.save(t);
        } catch (Exception e) {
            log.warn("Failed to persist decision trace: {}", e.getMessage());
        }
    }

    /** Records a REJECTED trace at the given gate and logs a consistent one-line reason. */
    private void reject(TraceCtx ctx, String stage, String reason) {
        ctx.note(stage, "REJECT — " + reason);
        log.info("[trace {}] {} REJECTED at {}: {}", ctx.cycleId, ctx.instrument, stage, reason);
        saveTrace(ctx, "REJECTED", stage, reason, 0);
    }

    public void evaluateMarketForSignals(MarketSnapshot latest, Double prevSpot) {
        evaluateMarketForSignals(latest, prevSpot, java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    @Transactional
    public void evaluateMarketForSignals(MarketSnapshot latest, Double prevSpot, String cycleId) {
        log.info("[cycle {}] Decision Agent executing trade signals search...", cycleId);

        // P5-2: everything below is scoped to this snapshot's instrument (NIFTY, BANKNIFTY, ...).
        String instrument = latest.getInstrument() != null ? latest.getInstrument() : "NIFTY";
        // Phase-0 trace: one record per evaluation pass so the gate funnel is queryable.
        TraceCtx trace = new TraceCtx(cycleId, instrument, latest.getSnapshotTime());

        com.nifty.analysis.instrument.InstrumentSpec spec = instrumentRegistry.get(instrument);
        if (spec == null) {
            log.warn("Unknown instrument '{}' — skipping evaluation.", instrument);
            reject(trace, "instrument", "unknown instrument " + instrument);
            return;
        }

        // 0. Risk guard: honour the kill switch and daily limits before opening any new trade.
        RiskGuardService.RiskCheck riskCheck = riskGuardService.canOpenNewTrade(instrument);
        if (!riskCheck.allowed()) {
            log.info("Risk guard blocked new trade: {}", riskCheck.reason());
            reject(trace, "risk_guard", riskCheck.reason());
            return;
        }

        // 0.5 Time-of-day filter: the volatile open (09:15–09:30) and theta-heavy close
        // (15:00–15:30) are riskier, but blocking them outright kills legitimate open-driven
        // moves. Soften to a higher confidence gate (applied below) instead of skipping.
        boolean volatileWindow = sessionFilterEnabled && inVolatileWindow();

        // 1. Fetch Option Snapshots to retrieve active option chain
        LocalDateTime latestOptionTime = optionSnapshotRepository.findLatestSnapshotTimeByInstrument(instrument);
        if (latestOptionTime == null) {
            log.warn("No option snapshots available for {}. Cannot evaluate trading signals.", instrument);
            reject(trace, "no_option_chain", "no option snapshots for " + instrument);
            return;
        }

        // DB-P16-3: don't trade against a stale option chain (e.g. the option feed froze while index
        // ticks kept flowing). Compare the chain's age to the market tick time.
        if (maxOptionStalenessSeconds > 0 && latest.getSnapshotTime() != null) {
            long optionAgeSec = java.time.Duration.between(latestOptionTime, latest.getSnapshotTime()).getSeconds();
            if (optionAgeSec > maxOptionStalenessSeconds) {
                reject(trace, "stale_option_chain",
                        "option chain " + optionAgeSec + "s older than market tick (max " + maxOptionStalenessSeconds + "s)");
                return;
            }
        }

        List<OptionSnapshot> optionChainEntities = optionSnapshotRepository.findByInstrumentAndSnapshotTime(instrument, latestOptionTime);

        // 1.5 Market regime: rather than skipping SIDEWAYS markets outright (which kills a
        // lot of valid scalps), allow them but demand extra confidence via a stricter gate.
        double effectiveGate = gatingThreshold;
        AgentResponse regimeResponse = marketRegimeAgent.analyze(instrument, latest.getSnapshotTime());
        if ("SIDEWAYS".equals(regimeResponse.bias())) {
            effectiveGate += sidewaysExtraGate;
            log.info("Sideways regime detected. Raising gate to {}% (instead of skipping).", effectiveGate);
        }
        if (volatileWindow) {
            effectiveGate += volatileWindowGatePenalty;
            log.info("Volatile session window: raising gate to {}% (instead of skipping).", effectiveGate);
        }
        trace.effectiveGate = effectiveGate;
        trace.note("regime", regimeResponse.bias() + " (score " + regimeResponse.score() + "), effectiveGate=" + effectiveGate);

        List<OptionSnapshotDto> optionChainDtos = optionChainEntities.stream().map(o -> new OptionSnapshotDto(
                o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                o.getIv(), o.getPcr(), o.getMaxPain(), o.getCeVolume(), o.getPeVolume(), o.getSnapshotTime(),
                o.getCeLtp(), o.getPeLtp()
        )).toList();

        double spotPrice = latest.getNiftySpot();
        double spotChange = prevSpot != null ? (spotPrice - prevSpot) : 0.0;

        // 2. Determine base trade direction (CE vs PE).
        boolean isBullish;
        boolean isBearish;
        if (directionConsensusEnabled) {
            // P3-3: require a CONSENSUS of independent signals, not one 1-minute technical tick.
            DirectionVote vote = voteDirection(latest, optionChainDtos, spotPrice, spotChange);
            trace.note("direction_consensus", "bull=" + vote.bull() + ", bear=" + vote.bear()
                    + ", participated=" + vote.participated() + "/4, need " + minDirectionAgreement);
            if (vote.bull() >= minDirectionAgreement && vote.bull() > vote.bear()) {
                isBullish = true; isBearish = false;
            } else if (vote.bear() >= minDirectionAgreement && vote.bear() > vote.bull()) {
                isBullish = false; isBearish = true;
            } else {
                log.info("No directional consensus (bull={}, bear={}, participated={}/4, need {}). Skipping.",
                        vote.bull(), vote.bear(), vote.participated(), minDirectionAgreement);
                reject(trace, "direction_consensus",
                        "no consensus (bull=" + vote.bull() + ", bear=" + vote.bear()
                                + ", participated=" + vote.participated() + ", need " + minDirectionAgreement + ")");
                return;
            }
        } else {
            AgentResponse technicalBias = technicalAgent.analyze(latest);
            isBullish = "BULLISH".equals(technicalBias.bias());
            isBearish = "BEARISH".equals(technicalBias.bias());
            if (!isBullish && !isBearish) {
                log.info("Market bias is Neutral. Skipping trade evaluation.");
                reject(trace, "neutral_bias", "technical bias neutral");
                return;
            }
        }

        String signalType = isBullish ? "BUY_CE" : "BUY_PE";
        trace.direction = isBullish ? "BULLISH" : "BEARISH";
        int atmStrike = spec.atmStrike(spotPrice);

        // 2.5 Momentum confirmation: a single opposing 1-min momentum read shouldn't veto a
        // multi-signal consensus (that reintroduces the tick-noise the consensus removes). Apply a
        // confidence penalty instead of a hard skip (Phase-1 fix DA-F6).
        double momentumPenalty = 0.0;
        if (momentumConfirmationEnabled) {
            AgentResponse momentum = marketAgent.analyze(latest, prevSpot);
            if ((isBullish && "BEARISH".equals(momentum.bias())) || (isBearish && "BULLISH".equals(momentum.bias()))) {
                momentumPenalty = momentumOppositionPenalty;
                log.info("Momentum ({}) opposes {}. Applying -{} confidence penalty (instead of skipping).",
                        momentum.bias(), signalType, momentumOppositionPenalty);
                trace.note("momentum", "opposing " + momentum.bias() + " → -" + momentumOppositionPenalty + " penalty");
            }
        }

        // 2.6 Entry timing: an over-extended-above-VWAP CE entry is chasing. Rather than skip
        // outright, apply a confidence penalty so only high-conviction chases still trade.
        double entryTimingPenalty = 0.0;
        if (entryTimingEnabled && isBullish) {
            AgentResponse timing = entryTimingAgent.validateEntry(latest, prevSpot);
            if (timing.score() <= 30.0) {
                entryTimingPenalty = entryOverextensionPenalty;
                log.info("Entry timing flags over-extension above VWAP. Applying -{} confidence penalty (instead of skipping).",
                        entryOverextensionPenalty);
            }
        }

        // 3. Compute ONNX model probability (direction-aware)
        TechnicalAgent.TechnicalFeatures features = technicalAgent.getFeatures(latest);
        double modelBullishProb = onnxModelService.predictBullishProbability(
                features.rsi(),
                features.spotToEma20(),
                features.ema20ToEma50(),
                features.vix(),
                features.prevDailyReturn(),
                features.bbWidth(),
                features.macdHist(),
                features.volumeRatio()
        );
        double modelConfidence = isBullish ? modelBullishProb : 100.0 - modelBullishProb;

        // Compute the rule-based multi-agent weighted confidence (direction-aware)
        ConfidenceEngine.RawConfidenceResult rawResult = confidenceEngine.calculateRawConfidence(latest, optionChainDtos, spotChange, isBullish);
        double agentConfidence = rawResult.rawConfidence();

        // Blend the ONNX model with the multi-agent score. The model is only trusted
        // once it is loaded AND enough history exists for its features to be meaningful.
        // Otherwise we fall back to the rule-based agent score to avoid the cold-start
        // problem where the model returns a flat ~50% and blocks every single trade.
        // Once enough history exists it never drops below the threshold, so stop counting
        // the (growing) table every cycle.
        if (!historySufficient && marketSnapshotRepository.count() >= modelMinHistory) {
            historySufficient = true;
        }
        boolean modelReady = onnxModelService.isModelLoaded() && historySufficient;
        double rawConfidence;
        if (modelReady) {
            rawConfidence = (modelWeight * modelConfidence) + ((1.0 - modelWeight) * agentConfidence);
            log.info("Confidence blend -> ONNX={}% (w={}), Agent={}% => Raw={}% (Direction: {})",
                    Math.round(modelConfidence * 100.0) / 100.0, modelWeight,
                    Math.round(agentConfidence * 100.0) / 100.0,
                    Math.round(rawConfidence * 100.0) / 100.0, isBullish ? "BULLISH" : "BEARISH");
        } else {
            rawConfidence = agentConfidence;
            log.info("ONNX model not ready (loaded={}, history-sufficient={}). Using rule-based agent confidence: {}% (Direction: {})",
                    onnxModelService.isModelLoaded(), historySufficient,
                    Math.round(agentConfidence * 100.0) / 100.0, isBullish ? "BULLISH" : "BEARISH");
        }
        rawConfidence = Math.round(rawConfidence * 100.0) / 100.0;

        // 4. Critic Agent invalidation checks
        CriticAgent.CriticResult criticResult = criticAgent.evaluateAndApplyPenalties(
                rawConfidence, latest, optionChainEntities, isBullish
        );

        double finalConfidence = Math.max(0.0, criticResult.adjustedConfidence() - entryTimingPenalty - momentumPenalty);
        log.info("Final evaluated signal confidence: {}% (Effective threshold = {}%)", finalConfidence, effectiveGate);
        trace.finalConfidence = finalConfidence;
        trace.note("confidence", "final=" + finalConfidence + " (raw=" + rawConfidence
                + ", entryPenalty=" + entryTimingPenalty + ", momentumPenalty=" + momentumPenalty + "), gate=" + effectiveGate);

        // 5. Gating: Signal generated only if adjusted confidence >= the effective gate.
        if (finalConfidence < effectiveGate) {
            log.info("Signal confidence ({}%) below threshold ({}%). NO TRADE.", finalConfidence, effectiveGate);
            reject(trace, "confidence_gate", "confidence " + finalConfidence + " < gate " + effectiveGate);
            return;
        }

        // 5.5 P3-1 minimum-confirmation gate: a high blended score can come from one collinear
        // trend factor counted ~3x. Require independent evidence — trend/structure AND order
        // flow (OI build-up or futures basis) AND a non-opposing PCR — before emitting.
        if (minConfirmationEnabled && !hasMinimumConfirmation(rawResult.factorScores())) {
            log.info("Insufficient independent confirmation (need trend + flow + non-opposing PCR). NO TRADE.");
            reject(trace, "min_confirmation", "need trend + flow + non-opposing PCR; factors=" + rawResult.factorScores());
            return;
        }

        // 5.6 P4-1 calibrated-probability gate: once enough resolved trades exist, require the
        // MEASURED probability of winning to clear break-even (from the reward:risk) + a margin —
        // a confidence of "80 points" only trades if history says 80 actually wins often enough.
        if (calibrationEnabled && calibrator.isTrained()) {
            double pWin = calibrator.probabilityOfWin(finalConfidence);
            // Cap the demanded win-rate so an unviable R:R-driven break-even (~0.91) can't fully
            // close the engine; calibration still tightens up to the cap.
            double required = Math.min(calibrator.breakEvenWinRate() + calibrationMargin, calibrationMaxRequiredWinRate);
            if (pWin < required) {
                log.info("Calibrated P(win)={}% below required {}% (break-even+margin). NO TRADE.",
                        Math.round(pWin * 10000.0) / 100.0, Math.round(required * 10000.0) / 100.0);
                reject(trace, "calibration", "P(win)=" + Math.round(pWin * 10000.0) / 100.0
                        + "% < required " + Math.round(required * 10000.0) / 100.0 + "%");
                return;
            }
            log.info("Calibrated P(win)={}% clears required {}%.",
                    Math.round(pWin * 10000.0) / 100.0, Math.round(required * 10000.0) / 100.0);
        }

        // 6. Generate the direction-level thesis once and reuse it across the strike ladder
        // (avoids 3x LLM cost; the thesis is about market direction, not the specific strike).
        StringBuilder criticSummary = new StringBuilder();
        for (CriticAgent.PenaltyDetails p : criticResult.appliedPenalties()) {
            criticSummary.append(p.comment()).append("; ");
        }
        String thesis = llmService.generateTradeExplanation(
                signalType, atmStrike, finalConfidence, rawResult.factorScores(), criticSummary.toString());

        // 6.5 Pick a strategy by regime, then DELEGATE emission (build/price/execute/persist/notify)
        // to SignalEmissionService (Phase-3 SRP: DecisionAgent decides; the service emits).
        com.nifty.analysis.strategy.StrategyType strategy =
                strategySelector.select(regimeResponse.bias(), isBullish);
        com.nifty.analysis.service.SignalEmissionService.EmissionResult emission = signalEmissionService.emit(
                spec, strategy, atmStrike, isBullish, signalType, spotPrice, finalConfidence,
                modelConfidence, agentConfidence, rawConfidence, modelReady, modelWeight,
                rawResult, criticResult, optionChainEntities, thesis, latest.getIndiaVix());

        if (emission.emitted() > 0) {
            trace.note("emit", emission.multiLeg()
                    ? ("multi-leg " + strategy + " @ " + atmStrike)
                    : (emission.emitted() + " of " + emission.candidates() + " ladder strikes"));
            saveTrace(trace, "EMITTED", null, null, emission.emitted());
        } else {
            reject(trace, emission.multiLeg() ? "multileg_guard" : "per_strike_filters",
                    emission.multiLeg()
                            ? "multi-leg guard skipped emission"
                            : "0 of " + emission.candidates() + " strikes passed liquidity/duplicate/exposure");
        }
    }

    /** True during the volatile open (09:15–09:30) and theta-heavy close (15:00–15:30) IST windows. */
    private boolean inVolatileWindow() {
        java.time.ZonedDateTime ist = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.DayOfWeek day = ist.getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) {
            return false; // weekend runs are simulation; don't block.
        }
        java.time.LocalTime t = ist.toLocalTime();
        boolean openWhipsaw = !t.isBefore(java.time.LocalTime.of(9, 15)) && t.isBefore(java.time.LocalTime.of(9, 30));
        boolean lateSessionTheta = !t.isBefore(java.time.LocalTime.of(15, 0)) && t.isBefore(java.time.LocalTime.of(15, 30));
        return openWhipsaw || lateSessionTheta;
    }

    /** P3-3 vote tally from independent direction signals (participated = voters that took a side). */
    private record DirectionVote(int bull, int bear, int participated) {}

    /**
     * Counts bullish vs bearish votes across four INDEPENDENT signals:
     * technical bias, multi-timeframe trend, futures basis sign, and OI build-up bias.
     */
    private DirectionVote voteDirection(MarketSnapshot latest, List<OptionSnapshotDto> optionChain,
                                        double spotPrice, double spotChange) {
        int bull = 0, bear = 0;

        // 1. Technical bias (EMA/RSI/structure)
        String tech = technicalAgent.analyze(latest).bias();
        if ("BULLISH".equals(tech)) bull++; else if ("BEARISH".equals(tech)) bear++;

        // 2. Multi-timeframe trend (scoped to this instrument)
        double mtf = multiTimeframeAgent.analyze(latest.getInstrument(), latest.getSnapshotTime()).score();
        if (mtf >= 55.0) bull++; else if (mtf <= 45.0) bear++;

        // 3. Futures basis vs cost-of-carry fair value (P3-4: normalized by days-to-expiry)
        if (latest.getNiftyFuture() != null) {
            double premium = latest.getNiftyFuture() - spotPrice;
            long dte = com.nifty.analysis.util.TimeUtil.daysToWeeklyExpiry(latest.getSnapshotTime().toLocalDate());
            if (ConfidenceEngine.futuresBasisScore(premium, spotPrice, dte, true) == 100.0) bull++;
            else if (ConfidenceEngine.futuresBasisScore(premium, spotPrice, dte, false) == 100.0) bear++;
        }

        // 4. Options OI build-up bias
        String oi = optionsAgent.analyze(optionChain, spotPrice, spotChange).bias();
        if ("BULLISH".equals(oi)) bull++; else if ("BEARISH".equals(oi)) bear++;

        int participated = bull + bear;
        log.info("Direction votes -> bull={}, bear={}, participated={}/4 (technical={}, mtf={}, oi={})",
                bull, bear, participated, tech, mtf, oi);
        return new DirectionVote(bull, bear, participated);
    }

    /**
     * P3-1: require independent confirmation. Trend/structure (Trend, MultiTimeframe, VWAP are
     * collinear → treat as ONE group, strongest wins) AND order flow (OI build-up OR futures
     * basis) AND a non-opposing PCR. Scores are direction-aware (100 favorable / 50 neutral / 0 opposing).
     */
    private boolean hasMinimumConfirmation(Map<String, Double> f) {
        double trend = Math.max(factor(f, "Trend"), Math.max(factor(f, "MultiTimeframe"), factor(f, "VWAP")));
        boolean trendOk = trend >= confirmScore;
        boolean flowOk = factor(f, "OI") >= confirmScore || factor(f, "Futures") >= confirmScore;
        boolean pcrOk = factor(f, "PCR") >= notOpposingScore;
        if (!(trendOk && flowOk && pcrOk)) {
            log.info("Confirmation check: trend={} (ok={}), flow[OI={}, Fut={}] (ok={}), PCR={} (ok={})",
                    trend, trendOk, factor(f, "OI"), factor(f, "Futures"), flowOk, factor(f, "PCR"), pcrOk);
        }
        return trendOk && flowOk && pcrOk;
    }

    // A missing factor means "no data", which is NEUTRAL (50), not maximally-opposing (0). Defaulting
    // to 0 made the min-confirmation gate fail closed whenever a factor was absent (Phase-1 fix DA-F2).
    private static double factor(Map<String, Double> f, String key) {
        return f.getOrDefault(key, 50.0);
    }
}
