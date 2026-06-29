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

    @Value("${nifty.risk.target-profit-percent:2.0}")
    private double targetProfitPercent;

    @Value("${nifty.risk.stop-loss-percent:40.0}")
    private double stopLossPercent;

    // --- Signal generation tuning (more trades + higher accuracy) ---
    @Value("${nifty.signal.strike-ladder-enabled:true}")
    private boolean strikeLadderEnabled;

    @Value("${nifty.signal.strike-step:50}")
    private int strikeStep;

    @Value("${nifty.signal.sideways-extra-gate:8.0}")
    private double sidewaysExtraGate;

    @Value("${nifty.signal.min-liquidity-score:70.0}")
    private double minLiquidityScore;

    @Value("${nifty.signal.momentum-confirmation-enabled:true}")
    private boolean momentumConfirmationEnabled;

    @Value("${nifty.signal.entry-timing-enabled:true}")
    private boolean entryTimingEnabled;

    @Value("${nifty.timing.session-filter-enabled:true}")
    private boolean sessionFilterEnabled;

    // Soften (don't skip) the volatile open/close window: raise the confidence gate by this many
    // points instead of blocking outright, so a strong open-driven move can still trade.
    @Value("${nifty.timing.volatile-window-gate-penalty:10.0}")
    private double volatileWindowGatePenalty;

    // Soften (don't skip) an over-extended-above-VWAP entry: subtract this confidence penalty
    // instead of blocking, so chasing setups need extra conviction rather than being banned.
    @Value("${nifty.signal.entry-overextension-penalty:15.0}")
    private double entryOverextensionPenalty;

    // Aggregate exposure cap: max simultaneously-open (ACTIVE) positions across all ladders.
    @Value("${nifty.risk.max-concurrent-positions:6}")
    private int maxConcurrentPositions;

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
    @Value("${nifty.signal.min-direction-agreement:3}")
    private int minDirectionAgreement; // of 4 votes: technical, multi-timeframe, futures, OI

    // --- P5-1: defined-risk multi-leg strategy params ---
    @Value("${nifty.strategy.spread-width-steps:2}")
    private int spreadWidthSteps;                 // spread/wing width = this many strike steps
    @Value("${nifty.strategy.target-fraction:0.6}")
    private double multiLegTargetFraction;        // exit a multi-leg at this fraction of max profit

    // --- P4-1: gate on calibrated probability of winning, not raw confidence points ---
    @Value("${nifty.calibration.enabled:true}")
    private boolean calibrationEnabled;
    @Value("${nifty.calibration.margin:0.0}")
    private double calibrationMargin; // extra cushion above break-even win-rate (0..1)

    // Cached once enough snapshot history exists, to avoid counting a growing table each cycle.
    private volatile boolean historySufficient = false;

    private final ConfidenceEngine confidenceEngine;
    private final CriticAgent criticAgent;
    private final TechnicalAgent technicalAgent;
    private final OptionsAgent optionsAgent;
    private final SentimentAgent sentimentAgent;
    private final MarketAgent marketAgent;
    private final LiquidityAgent liquidityAgent;
    private final EntryTimingAgent entryTimingAgent;
    private final RiskAgent riskAgent;

    private final MarketRegimeAgent marketRegimeAgent;
    private final MultiTimeframeAgent multiTimeframeAgent;
    private final com.nifty.analysis.engine.ConfidenceCalibrator calibrator;
    private final com.nifty.analysis.instrument.InstrumentRegistry instrumentRegistry;
    private final com.nifty.analysis.strategy.RegimeStrategySelector strategySelector;
    private final com.nifty.analysis.repository.TradeLegRepository tradeLegRepository;
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final TradeSignalRepository tradeSignalRepository;
    private final SignalExplanationRepository signalExplanationRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final TelegramBotService telegramBotService;
    private final LlmService llmService;
    private final OrderExecutionService orderExecutionService;
    private final OnnxModelService onnxModelService;
    private final RiskGuardService riskGuardService;
    private final OptionPricingService optionPricingService;

    @Transactional
    public void evaluateMarketForSignals(MarketSnapshot latest, Double prevSpot) {
        log.info("Decision Agent executing trade signals search...");

        // P5-2: everything below is scoped to this snapshot's instrument (NIFTY, BANKNIFTY, ...).
        String instrument = latest.getInstrument() != null ? latest.getInstrument() : "NIFTY";
        com.nifty.analysis.instrument.InstrumentSpec spec = instrumentRegistry.get(instrument);
        if (spec == null) {
            log.warn("Unknown instrument '{}' — skipping evaluation.", instrument);
            return;
        }

        // 0. Risk guard: honour the kill switch and daily limits before opening any new trade.
        RiskGuardService.RiskCheck riskCheck = riskGuardService.canOpenNewTrade(instrument);
        if (!riskCheck.allowed()) {
            log.info("Risk guard blocked new trade: {}", riskCheck.reason());
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
            return;
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
            if (vote.bull() >= minDirectionAgreement && vote.bull() > vote.bear()) {
                isBullish = true; isBearish = false;
            } else if (vote.bear() >= minDirectionAgreement && vote.bear() > vote.bull()) {
                isBullish = false; isBearish = true;
            } else {
                log.info("No directional consensus (bull={}, bear={}, need {} of 4). Skipping.",
                        vote.bull(), vote.bear(), minDirectionAgreement);
                return;
            }
        } else {
            AgentResponse technicalBias = technicalAgent.analyze(latest);
            isBullish = "BULLISH".equals(technicalBias.bias());
            isBearish = "BEARISH".equals(technicalBias.bias());
            if (!isBullish && !isBearish) {
                log.info("Market bias is Neutral. Skipping trade evaluation.");
                return;
            }
        }

        String signalType = isBullish ? "BUY_CE" : "BUY_PE";
        int atmStrike = spec.atmStrike(spotPrice);

        // 2.5 Momentum confirmation: don't fight a strong opposing momentum tick.
        if (momentumConfirmationEnabled) {
            AgentResponse momentum = marketAgent.analyze(latest, prevSpot);
            if ((isBullish && "BEARISH".equals(momentum.bias())) || (isBearish && "BULLISH".equals(momentum.bias()))) {
                log.info("Momentum ({}) opposes {} direction. Skipping to avoid fighting the tape.", momentum.bias(), signalType);
                return;
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

        double finalConfidence = Math.max(0.0, criticResult.adjustedConfidence() - entryTimingPenalty);
        log.info("Final evaluated signal confidence: {}% (Effective threshold = {}%)", finalConfidence, effectiveGate);

        // 5. Gating: Signal generated only if adjusted confidence >= the effective gate.
        if (finalConfidence < effectiveGate) {
            log.info("Signal confidence ({}%) below threshold ({}%). NO TRADE.", finalConfidence, effectiveGate);
            return;
        }

        // 5.5 P3-1 minimum-confirmation gate: a high blended score can come from one collinear
        // trend factor counted ~3x. Require independent evidence — trend/structure AND order
        // flow (OI build-up or futures basis) AND a non-opposing PCR — before emitting.
        if (minConfirmationEnabled && !hasMinimumConfirmation(rawResult.factorScores())) {
            log.info("Insufficient independent confirmation (need trend + flow + non-opposing PCR). NO TRADE.");
            return;
        }

        // 5.6 P4-1 calibrated-probability gate: once enough resolved trades exist, require the
        // MEASURED probability of winning to clear break-even (from the reward:risk) + a margin —
        // a confidence of "80 points" only trades if history says 80 actually wins often enough.
        if (calibrationEnabled && calibrator.isTrained()) {
            double pWin = calibrator.probabilityOfWin(finalConfidence);
            double required = calibrator.breakEvenWinRate() + calibrationMargin;
            if (pWin < required) {
                log.info("Calibrated P(win)={}% below required {}% (break-even+margin). NO TRADE.",
                        Math.round(pWin * 10000.0) / 100.0, Math.round(required * 10000.0) / 100.0);
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

        // 6.5 P5-1: pick a strategy by regime. Multi-leg (defined-risk spread / iron condor) replaces
        // the single-leg ladder. Config-gated: when spreads-enabled is off, this is always LONG_CALL/PUT.
        com.nifty.analysis.strategy.StrategyType strategy =
                strategySelector.select(regimeResponse.bias(), isBullish);
        if (strategy.isMultiLeg()) {
            emitMultiLegSignal(spec, strategy, atmStrike, finalConfidence, latest.getIndiaVix(), thesis);
            return;
        }

        // 7. Strike ladder: emit a signal for every liquid, non-duplicate candidate strike
        // (ITM / ATM / OTM). ITM has the highest delta so it captures the 2% premium move
        // most reliably; ATM/OTM add trade count.
        List<Integer> candidateStrikes = buildCandidateStrikes(atmStrike, isBullish, spec.strikeStep());
        int emitted = 0;
        double vix = latest.getIndiaVix();
        int ladderLegs = candidateStrikes.size();
        for (int strike : candidateStrikes) {
            if (emitSignalForStrike(spec, strike, signalType, isBullish, spotPrice, finalConfidence,
                    modelConfidence, agentConfidence, rawConfidence, modelReady, rawResult, criticResult,
                    optionChainEntities, thesis, vix, ladderLegs)) {
                emitted++;
            }
        }
        log.info("Strike-ladder evaluation complete: {} signal(s) emitted from {} candidate strike(s).",
                emitted, candidateStrikes.size());
    }

    /**
     * P5-1: emits ONE defined-risk multi-leg signal (spread / iron condor) and its legs. Paper-tracked
     * (live multi-leg order placement is not wired yet); resolution is by NET P&L against the capped
     * max-profit/max-loss. SL/target fields hold INR thresholds here (multi-leg resolution branches on
     * the strategy tag, so it never uses them as premium levels).
     */
    private void emitMultiLegSignal(com.nifty.analysis.instrument.InstrumentSpec spec,
            com.nifty.analysis.strategy.StrategyType strategy, int atmStrike, double finalConfidence,
            double vix, String thesis) {
        if (tradeSignalRepository.countByStatus("ACTIVE") >= maxConcurrentPositions) {
            log.info("Max concurrent positions reached. Skipping {} {}.", spec.name(), strategy);
            return;
        }
        Boolean dir = strategy.bullish();
        String repType = (dir == null || dir) ? "BUY_CE" : "BUY_PE"; // representative tag for display/guard
        if (tradeSignalRepository.findFirstByInstrumentAndStrikeAndSignalTypeAndStatus(
                spec.name(), atmStrike, repType, "ACTIVE").isPresent()) {
            log.info("Active {} {} already exists at {}. Skipping.", spec.name(), strategy, atmStrike);
            return;
        }

        // Per-leg premium: live LTP, else a simple distance-decayed nominal (paper/sim).
        java.util.function.BiFunction<String, Integer, Double> premiumFn = (type, strike) -> {
            double ltp = optionPricingService.getOptionLtp("CE".equals(type) ? "BUY_CE" : "BUY_PE", strike);
            if (ltp > 0) return ltp;
            double otm = "CE".equals(type) ? Math.max(0, strike - atmStrike) : Math.max(0, atmStrike - strike);
            return Math.max(5.0, 120.0 - otm * 0.4);
        };

        int qty = orderExecutionService.calculateQuantity(premiumFn.apply("CE", atmStrike), 1, spec.lotSize());
        com.nifty.analysis.strategy.StrategyBuilder.Built built = com.nifty.analysis.strategy.StrategyBuilder.build(
                strategy, atmStrike, spec.strikeStep(), spreadWidthSteps, premiumFn, qty);

        TradeSignal signal = new TradeSignal();
        signal.setInstrument(spec.name());
        signal.setSignalTime(com.nifty.analysis.util.TimeUtil.nowIst());
        signal.setSignalType(repType);
        signal.setStrategy(strategy.name());
        signal.setStrike(atmStrike);
        signal.setEntry(round2(built.netPremiumPerUnit()));   // signed net (credit +, debit −) per unit
        signal.setQuantity(qty);
        signal.setStopLoss(round2(built.maxLossInr()));        // INR: defined-risk cap
        signal.setTarget1(round2(built.maxProfitInr() * multiLegTargetFraction));
        signal.setTarget2(round2(built.maxProfitInr() * multiLegTargetFraction)); // INR: take-profit
        signal.setConfidence(round2(finalConfidence));
        signal.setStatus("ACTIVE");
        signal.setThesis(thesis);
        tradeSignalRepository.save(signal);

        List<com.nifty.analysis.entity.TradeLeg> legs = new ArrayList<>();
        for (com.nifty.analysis.strategy.StrategyBuilder.Leg l : built.legs()) {
            com.nifty.analysis.entity.TradeLeg leg = new com.nifty.analysis.entity.TradeLeg();
            leg.setSignal(signal);
            leg.setAction(l.action());
            leg.setOptionType(l.optionType());
            leg.setStrike(l.strike());
            leg.setEntryPremium(round2(l.premium()));
            leg.setQuantity(qty);
            legs.add(leg);
        }
        tradeLegRepository.saveAll(legs);

        SignalExplanation exp = explanation("Final_Confidence", round2(finalConfidence), "Multi-leg " + strategy);
        exp.setSignal(signal);
        signalExplanationRepository.saveAll(List.of(exp));

        telegramBotService.sendSignal(signal, List.of(
                "*Strategy:* " + strategy + " (defined risk)",
                "*Thesis:* " + thesis,
                String.format("Max profit %.0f / Max loss %.0f INR (%d legs)",
                        built.maxProfitInr(), built.maxLossInr(), legs.size())));
        log.info("Emitted {} {} multi-leg signal id={} ({} legs, net={}, maxProfit={}, maxLoss={}).",
                spec.name(), strategy, signal.getId(), legs.size(), signal.getEntry(),
                built.maxProfitInr(), built.maxLossInr());
    }

    /** Builds the ITM/ATM/OTM candidate strikes for the ladder (ITM first), using the instrument's step. */
    private List<Integer> buildCandidateStrikes(int atmStrike, boolean isBullish, int step) {
        if (!strikeLadderEnabled) {
            return List.of(atmStrike);
        }
        int itm = isBullish ? atmStrike - step : atmStrike + step;
        int otm = isBullish ? atmStrike + step : atmStrike - step;
        return List.of(itm, atmStrike, otm);
    }

    /** Emits one signal for a strike if it is non-duplicate and liquid. Returns true if emitted. */
    private boolean emitSignalForStrike(com.nifty.analysis.instrument.InstrumentSpec spec, int strike, String signalType, boolean isBullish, double spotPrice,
            double finalConfidence, double modelConfidence, double agentConfidence, double rawConfidence,
            boolean modelReady, ConfidenceEngine.RawConfidenceResult rawResult, CriticAgent.CriticResult criticResult,
            List<OptionSnapshot> optionChainEntities, String thesis, double vix, int splitAcross) {

        // Aggregate exposure cap: stop opening new positions once the max are already open.
        long openPositions = tradeSignalRepository.countByStatus("ACTIVE");
        if (openPositions >= maxConcurrentPositions) {
            log.info("Max concurrent positions reached ({}/{}). Skipping strike {}.",
                    openPositions, maxConcurrentPositions, strike);
            return false;
        }

        // Per-strike duplicate guard (scoped to the instrument)
        if (tradeSignalRepository.findFirstByInstrumentAndStrikeAndSignalTypeAndStatus(
                spec.name(), strike, signalType, "ACTIVE").isPresent()) {
            log.info("Active {} signal already exists for strike {} {}. Skipping.", spec.name(), strike, signalType);
            return false;
        }

        // Per-strike liquidity guard — never trade an illiquid strike where the 2% target is just spread.
        OptionSnapshot strikeSnap = optionChainEntities.stream()
                .filter(o -> o.getStrikePrice() != null && o.getStrikePrice() == strike)
                .findFirst().orElse(null);
        if (strikeSnap == null) {
            log.info("No option snapshot for strike {}. Skipping.", strike);
            return false;
        }
        AgentResponse liquidity = liquidityAgent.evaluateStrike(strikeSnap, isBullish);
        if (liquidity.score() < minLiquidityScore) {
            log.info("Strike {} liquidity {}% below minimum {}%. Skipping.", strike, liquidity.score(), minLiquidityScore);
            return false;
        }

        // Pricing from the real option premium (LTP), with fallback. Target/SL formulas unchanged.
        double entry = optionPricingService.getOptionLtp(signalType, strike);
        if (entry <= 0) {
            entry = 150.0; // fallback when live LTP is unavailable (simulation / no broker session)
            log.info("Live option LTP unavailable for {} {}. Using fallback entry premium {}.", signalType, strike, entry);
        }
        double target1 = round2(entry * (1.0 + targetProfitPercent / 200.0));
        double target2 = round2(entry * (1.0 + targetProfitPercent / 100.0));
        double stopLoss = round2(entry * (1.0 - stopLossPercent / 100.0));
        int quantity = orderExecutionService.calculateQuantity(entry, splitAcross, spec.lotSize());

        // Risk assessment (advisory): evaluate R:R + volatility risk. Surfaced/logged for
        // every signal — NOT a hard block (the configured 2% target / 40% stop intentionally
        // scores low here; blocking would suppress all trades).
        AgentResponse risk = riskAgent.evaluateRisk(entry, stopLoss, target1, vix);
        if (!"BULLISH".equals(risk.bias())) {
            log.warn("Risk advisory UNFAVOURABLE for {} {} (score={}%): {}", signalType, strike,
                    round2(risk.score()), String.join("; ", risk.comments()));
        }

        // Place the order FIRST, then only persist an ACTIVE signal if it actually went
        // through (PLACED) or is an intentional paper/simulated trade (SKIPPED). A FAILED
        // live order must NOT leave a phantom ACTIVE position with no real fill behind it.
        OrderExecutionService.OrderResult order =
                orderExecutionService.executeOrder(signalType, strike, spotPrice, splitAcross);
        if (order.outcome() == OrderExecutionService.OrderResult.Outcome.FAILED) {
            log.warn("Order FAILED for {} {} — not creating a phantom ACTIVE signal.", signalType, strike);
            return false;
        }

        TradeSignal signal = new TradeSignal();
        signal.setInstrument(spec.name());
        signal.setSignalTime(com.nifty.analysis.util.TimeUtil.nowIst());
        signal.setSignalType(signalType);
        signal.setStrike(strike);
        signal.setEntry(round2(entry));
        signal.setQuantity(quantity);
        signal.setStopLoss(stopLoss);
        signal.setTarget1(target1);
        signal.setTarget2(target2);
        signal.setConfidence(finalConfidence);
        signal.setStatus("ACTIVE");
        signal.setThesis(thesis);
        signal.setOrderId(order.orderId()); // broker order id when PLACED; null for paper
        signal.setEntrySpot(round2(spotPrice)); // capture entry spot instead of reconstructing later
        tradeSignalRepository.save(signal);

        // Explanations: provenance + factor scores + critic penalties + liquidity.
        List<SignalExplanation> explanations = new ArrayList<>();
        explanations.add(explanation("Model_ONNX", round2(modelConfidence),
                "ONNX model directional confidence" + (modelReady ? "" : " (NOT used — model not ready)")));
        explanations.add(explanation("Agent_Weighted", round2(agentConfidence),
                "Rule-based multi-agent weighted confidence"));
        explanations.add(explanation("Blended_Raw", round2(rawConfidence),
                modelReady
                        ? String.format("Blend: %.2f*ONNX + %.2f*Agent", modelWeight, 1.0 - modelWeight)
                        : "Agent-only (ONNX not ready)"));
        explanations.add(explanation("Final_Confidence", round2(finalConfidence),
                String.format("After critic penalties; gating threshold = %.1f%%", gatingThreshold)));
        explanations.add(explanation("Liquidity", round2(liquidity.score()),
                "Strike liquidity score (" + strikeClass(strike, spotPrice, isBullish, spec.strikeStep()) + ")"));
        explanations.add(explanation("Risk_RR", round2(risk.score()),
                "Risk advisory: " + String.join("; ", risk.comments())));
        for (Map.Entry<String, Double> entryScore : rawResult.factorScores().entrySet()) {
            explanations.add(explanation(entryScore.getKey(), entryScore.getValue(),
                    "Factor raw score = " + entryScore.getValue()));
        }
        for (CriticAgent.PenaltyDetails penalty : criticResult.appliedPenalties()) {
            explanations.add(explanation(penalty.factor(), penalty.scoreAdjustment(), penalty.comment()));
        }
        for (SignalExplanation e : explanations) {
            e.setSignal(signal);
        }
        signalExplanationRepository.saveAll(explanations);
        log.info("Saved trade signal (id={}, strike={}) and {} scoring explanations.", signal.getId(), strike, explanations.size());

        // Notify via Telegram Bot
        List<String> reasons = new ArrayList<>();
        reasons.add("*Thesis:* " + thesis);
        reasons.add(isBullish ? "Bullish trend structure" : "Bearish trend structure");
        reasons.add("Strike " + strike + " (" + strikeClass(strike, spotPrice, isBullish, spec.strikeStep()) + ") — liquidity confirmed");
        reasons.add((("BULLISH".equals(risk.bias())) ? "Risk OK: " : "⚠️ Risk: ") + String.join("; ", risk.comments()));
        for (CriticAgent.PenaltyDetails p : criticResult.appliedPenalties()) {
            reasons.add("Critic Penalty: " + p.comment());
        }
        telegramBotService.sendSignal(signal, reasons);
        return true;
    }

    /** Classifies a strike as ITM/ATM/OTM relative to spot for the given direction. */
    private static String strikeClass(int strike, double spot, boolean isBullish, int step) {
        int atm = (int) (Math.round(spot / step) * step);
        if (strike == atm) {
            return "ATM";
        }
        boolean itm = isBullish ? strike < atm : strike > atm;
        return itm ? "ITM" : "OTM";
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

    /** P3-3 vote tally from independent direction signals. */
    private record DirectionVote(int bull, int bear) {}

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

        log.info("Direction votes -> bull={}, bear={} (technical={}, mtf={}, oi={})", bull, bear, tech, mtf, oi);
        return new DirectionVote(bull, bear);
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

    private static double factor(Map<String, Double> f, String key) {
        return f.getOrDefault(key, 0.0);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static SignalExplanation explanation(String factor, double score, String comment) {
        SignalExplanation exp = new SignalExplanation();
        exp.setFactor(factor);
        exp.setScore(score);
        exp.setComment(comment);
        return exp;
    }
}
