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

        // 0. Risk guard: honour the kill switch and daily limits before opening any new trade.
        RiskGuardService.RiskCheck riskCheck = riskGuardService.canOpenNewTrade();
        if (!riskCheck.allowed()) {
            log.info("Risk guard blocked new trade: {}", riskCheck.reason());
            return;
        }

        // 0.5 Time-of-day filter: avoid the volatile first minutes after open and the
        // theta-heavy final window. Only carves out those intraday windows.
        if (sessionFilterEnabled && inVolatileWindow()) {
            log.info("Within a volatile session window (open whipsaw / late-session theta). Skipping trade evaluation.");
            return;
        }

        // 1. Fetch Option Snapshots to retrieve active option chain
        LocalDateTime latestOptionTime = optionSnapshotRepository.findLatestSnapshotTime();
        if (latestOptionTime == null) {
            log.warn("No option snapshots available. Cannot evaluate trading signals.");
            return;
        }

        List<OptionSnapshot> optionChainEntities = optionSnapshotRepository.findBySnapshotTime(latestOptionTime);

        // 1.5 Market regime: rather than skipping SIDEWAYS markets outright (which kills a
        // lot of valid scalps), allow them but demand extra confidence via a stricter gate.
        double effectiveGate = gatingThreshold;
        AgentResponse regimeResponse = marketRegimeAgent.analyze(latest.getSnapshotTime());
        if ("SIDEWAYS".equals(regimeResponse.bias())) {
            effectiveGate += sidewaysExtraGate;
            log.info("Sideways regime detected. Raising gate to {}% (instead of skipping).", effectiveGate);
        }

        List<OptionSnapshotDto> optionChainDtos = optionChainEntities.stream().map(o -> new OptionSnapshotDto(
                o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                o.getIv(), o.getPcr(), o.getMaxPain(), o.getCeVolume(), o.getPeVolume(), o.getSnapshotTime()
        )).toList();

        double spotPrice = latest.getNiftySpot();
        double spotChange = prevSpot != null ? (spotPrice - prevSpot) : 0.0;

        // 2. Determine base trade direction (CE vs PE)
        AgentResponse technicalBias = technicalAgent.analyze(latest);
        boolean isBullish = "BULLISH".equals(technicalBias.bias());
        boolean isBearish = "BEARISH".equals(technicalBias.bias());

        if (!isBullish && !isBearish) {
            log.info("Market bias is Neutral. Skipping trade evaluation.");
            return;
        }

        String signalType = isBullish ? "BUY_CE" : "BUY_PE";
        int atmStrike = ((int) Math.round(spotPrice / 50.0)) * 50;

        // 2.5 Momentum confirmation: don't fight a strong opposing momentum tick.
        if (momentumConfirmationEnabled) {
            AgentResponse momentum = marketAgent.analyze(latest, prevSpot);
            if ((isBullish && "BEARISH".equals(momentum.bias())) || (isBearish && "BULLISH".equals(momentum.bias()))) {
                log.info("Momentum ({}) opposes {} direction. Skipping to avoid fighting the tape.", momentum.bias(), signalType);
                return;
            }
        }

        // 2.6 Entry timing: skip CE entries that are chasing an over-extended move above VWAP.
        if (entryTimingEnabled && isBullish) {
            AgentResponse timing = entryTimingAgent.validateEntry(latest, prevSpot);
            if (timing.score() <= 30.0) {
                log.info("Entry timing flags over-extension above VWAP. Waiting for a pullback. Skipping.");
                return;
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
        long snapshotCount = marketSnapshotRepository.count();
        boolean modelReady = onnxModelService.isModelLoaded() && snapshotCount >= modelMinHistory;
        double rawConfidence;
        if (modelReady) {
            rawConfidence = (modelWeight * modelConfidence) + ((1.0 - modelWeight) * agentConfidence);
            log.info("Confidence blend -> ONNX={}% (w={}), Agent={}% => Raw={}% (Direction: {})",
                    Math.round(modelConfidence * 100.0) / 100.0, modelWeight,
                    Math.round(agentConfidence * 100.0) / 100.0,
                    Math.round(rawConfidence * 100.0) / 100.0, isBullish ? "BULLISH" : "BEARISH");
        } else {
            rawConfidence = agentConfidence;
            log.info("ONNX model not ready (loaded={}, snapshots={}/{}). Using rule-based agent confidence: {}% (Direction: {})",
                    onnxModelService.isModelLoaded(), snapshotCount, modelMinHistory,
                    Math.round(agentConfidence * 100.0) / 100.0, isBullish ? "BULLISH" : "BEARISH");
        }
        rawConfidence = Math.round(rawConfidence * 100.0) / 100.0;

        // 4. Critic Agent invalidation checks
        CriticAgent.CriticResult criticResult = criticAgent.evaluateAndApplyPenalties(
                rawConfidence, latest, optionChainEntities, isBullish
        );

        double finalConfidence = criticResult.adjustedConfidence();
        log.info("Final evaluated signal confidence: {}% (Effective threshold = {}%)", finalConfidence, effectiveGate);

        // 5. Gating: Signal generated only if adjusted confidence >= the effective gate.
        if (finalConfidence < effectiveGate) {
            log.info("Signal confidence ({}%) below threshold ({}%). NO TRADE.", finalConfidence, effectiveGate);
            return;
        }

        // 6. Generate the direction-level thesis once and reuse it across the strike ladder
        // (avoids 3x LLM cost; the thesis is about market direction, not the specific strike).
        StringBuilder criticSummary = new StringBuilder();
        for (CriticAgent.PenaltyDetails p : criticResult.appliedPenalties()) {
            criticSummary.append(p.comment()).append("; ");
        }
        String thesis = llmService.generateTradeExplanation(
                signalType, atmStrike, finalConfidence, rawResult.factorScores(), criticSummary.toString());

        // 7. Strike ladder: emit a signal for every liquid, non-duplicate candidate strike
        // (ITM / ATM / OTM). ITM has the highest delta so it captures the 2% premium move
        // most reliably; ATM/OTM add trade count.
        List<Integer> candidateStrikes = buildCandidateStrikes(atmStrike, isBullish);
        int emitted = 0;
        double vix = latest.getIndiaVix();
        for (int strike : candidateStrikes) {
            if (emitSignalForStrike(strike, signalType, isBullish, spotPrice, finalConfidence,
                    modelConfidence, agentConfidence, rawConfidence, modelReady, rawResult, criticResult,
                    optionChainEntities, thesis, vix)) {
                emitted++;
            }
        }
        log.info("Strike-ladder evaluation complete: {} signal(s) emitted from {} candidate strike(s).",
                emitted, candidateStrikes.size());
    }

    /** Builds the ITM/ATM/OTM candidate strikes for the ladder (ITM first). */
    private List<Integer> buildCandidateStrikes(int atmStrike, boolean isBullish) {
        if (!strikeLadderEnabled) {
            return List.of(atmStrike);
        }
        int itm = isBullish ? atmStrike - strikeStep : atmStrike + strikeStep;
        int otm = isBullish ? atmStrike + strikeStep : atmStrike - strikeStep;
        return List.of(itm, atmStrike, otm);
    }

    /** Emits one signal for a strike if it is non-duplicate and liquid. Returns true if emitted. */
    private boolean emitSignalForStrike(int strike, String signalType, boolean isBullish, double spotPrice,
            double finalConfidence, double modelConfidence, double agentConfidence, double rawConfidence,
            boolean modelReady, ConfidenceEngine.RawConfidenceResult rawResult, CriticAgent.CriticResult criticResult,
            List<OptionSnapshot> optionChainEntities, String thesis, double vix) {

        // Per-strike duplicate guard
        if (tradeSignalRepository.findFirstByStrikeAndSignalTypeAndStatus(strike, signalType, "ACTIVE").isPresent()) {
            log.info("Active signal already exists for strike {} {}. Skipping.", strike, signalType);
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
        int quantity = orderExecutionService.calculateQuantity(entry);

        // Risk assessment (advisory): evaluate R:R + volatility risk. Surfaced/logged for
        // every signal — NOT a hard block (the configured 2% target / 40% stop intentionally
        // scores low here; blocking would suppress all trades).
        AgentResponse risk = riskAgent.evaluateRisk(entry, stopLoss, target1, vix);
        if (!"BULLISH".equals(risk.bias())) {
            log.warn("Risk advisory UNFAVOURABLE for {} {} (score={}%): {}", signalType, strike,
                    round2(risk.score()), String.join("; ", risk.comments()));
        }

        TradeSignal signal = new TradeSignal();
        signal.setSignalTime(LocalDateTime.now());
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
                "Strike liquidity score (" + strikeClass(strike, spotPrice, isBullish) + ")"));
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
        reasons.add("Strike " + strike + " (" + strikeClass(strike, spotPrice, isBullish) + ") — liquidity confirmed");
        reasons.add((("BULLISH".equals(risk.bias())) ? "Risk OK: " : "⚠️ Risk: ") + String.join("; ", risk.comments()));
        for (CriticAgent.PenaltyDetails p : criticResult.appliedPenalties()) {
            reasons.add("Critic Penalty: " + p.comment());
        }
        telegramBotService.sendSignal(signal, reasons);

        // Execute Order via Angel One
        orderExecutionService.executeOrder(signalType, strike, spotPrice);
        return true;
    }

    /** Classifies a strike as ITM/ATM/OTM relative to spot for the given direction. */
    private static String strikeClass(int strike, double spot, boolean isBullish) {
        int atm = ((int) Math.round(spot / 50.0)) * 50;
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
