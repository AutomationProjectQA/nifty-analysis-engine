package com.nifty.analysis.service;

import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.LiquidityAgent;
import com.nifty.analysis.agent.RiskAgent;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeLeg;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.instrument.InstrumentSpec;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeLegRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.strategy.StrategyBuilder;
import com.nifty.analysis.strategy.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase-3 (audit #217): the signal EMISSION responsibility extracted out of {@code DecisionAgent}.
 * Given a decision that has already cleared every gate, this builds the signal(s) — picks strikes,
 * checks per-strike guards (exposure cap, duplicate, liquidity), prices, places the order, persists
 * the signal + legs + explanations, and notifies. {@code DecisionAgent} now only decides; this emits.
 *
 * <p>The logic is moved verbatim from the old {@code DecisionAgent} emit methods (no behaviour change).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalEmissionService {

    @Value("${nifty.gating-threshold:80.0}")
    private double gatingThreshold;

    @Value("${nifty.risk.target-profit-percent:2.0}")
    private double targetProfitPercent;

    @Value("${nifty.risk.stop-loss-percent:40.0}")
    private double stopLossPercent;

    @Value("${nifty.signal.strike-ladder-enabled:true}")
    private boolean strikeLadderEnabled;

    @Value("${nifty.signal.min-liquidity-score:70.0}")
    private double minLiquidityScore;

    @Value("${nifty.risk.max-concurrent-positions:6}")
    private int maxConcurrentPositions;

    @Value("${nifty.strategy.spread-width-steps:2}")
    private int spreadWidthSteps;

    @Value("${nifty.strategy.target-fraction:0.6}")
    private double multiLegTargetFraction;

    private final LiquidityAgent liquidityAgent;
    private final RiskAgent riskAgent;
    private final TradeSignalRepository tradeSignalRepository;
    private final TradeLegRepository tradeLegRepository;
    private final SignalExplanationRepository signalExplanationRepository;
    private final TelegramBotService telegramBotService;
    private final OrderExecutionService orderExecutionService;
    private final OptionPricingService optionPricingService;

    /** Outcome of an emission pass: whether it was multi-leg, and how many signals were emitted. */
    public record EmissionResult(boolean multiLeg, int emitted, int candidates) {}

    /**
     * Emits the signal(s) for a decision that has already passed all gates. Returns how many were
     * actually created (0 if every candidate strike was filtered, or the multi-leg guard skipped).
     */
    public EmissionResult emit(InstrumentSpec spec, StrategyType strategy, int atmStrike, boolean isBullish,
            String signalType, double spotPrice, double finalConfidence, double modelConfidence,
            double agentConfidence, double rawConfidence, boolean modelReady, double modelWeight,
            ConfidenceEngine.RawConfidenceResult rawResult, CriticAgent.CriticResult criticResult,
            List<OptionSnapshot> optionChainEntities, String thesis, double vix) {

        if (strategy.isMultiLeg()) {
            boolean ok = emitMultiLegSignal(spec, strategy, atmStrike, finalConfidence, vix, thesis);
            return new EmissionResult(true, ok ? 1 : 0, 1);
        }

        List<Integer> candidateStrikes = buildCandidateStrikes(atmStrike, isBullish, spec.strikeStep());
        int emitted = 0;
        int ladderLegs = candidateStrikes.size();
        for (int strike : candidateStrikes) {
            if (emitSignalForStrike(spec, strike, signalType, isBullish, spotPrice, finalConfidence,
                    modelConfidence, agentConfidence, rawConfidence, modelReady, modelWeight, rawResult, criticResult,
                    optionChainEntities, thesis, vix, ladderLegs)) {
                emitted++;
            }
        }
        log.info("Strike-ladder evaluation complete: {} signal(s) emitted from {} candidate strike(s).",
                emitted, candidateStrikes.size());
        return new EmissionResult(false, emitted, candidateStrikes.size());
    }

    /**
     * P5-1: emits ONE defined-risk multi-leg signal (spread / iron condor) and its legs. Paper-tracked
     * (live multi-leg order placement is not wired yet). Returns true if a signal was created.
     */
    private boolean emitMultiLegSignal(InstrumentSpec spec, StrategyType strategy, int atmStrike,
            double finalConfidence, double vix, String thesis) {
        if (tradeSignalRepository.countByStatus("ACTIVE") >= maxConcurrentPositions) {
            log.info("Max concurrent positions reached. Skipping {} {}.", spec.name(), strategy);
            return false;
        }
        Boolean dir = strategy.bullish();
        String repType = (dir == null || dir) ? "BUY_CE" : "BUY_PE"; // representative tag for display/guard
        if (tradeSignalRepository.findFirstByInstrumentAndStrikeAndSignalTypeAndStatus(
                spec.name(), atmStrike, repType, "ACTIVE").isPresent()) {
            log.info("Active {} {} already exists at {}. Skipping.", spec.name(), strategy, atmStrike);
            return false;
        }

        // Per-leg premium: live LTP, else a simple distance-decayed nominal (paper/sim).
        java.util.function.BiFunction<String, Integer, Double> premiumFn = (type, strike) -> {
            double ltp = optionPricingService.getOptionLtp("CE".equals(type) ? "BUY_CE" : "BUY_PE", strike);
            if (ltp > 0) return ltp;
            double otm = "CE".equals(type) ? Math.max(0, strike - atmStrike) : Math.max(0, atmStrike - strike);
            return Math.max(5.0, 120.0 - otm * 0.4);
        };

        int qty = orderExecutionService.calculateQuantity(premiumFn.apply("CE", atmStrike), 1, spec.lotSize());
        StrategyBuilder.Built built = StrategyBuilder.build(
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

        List<TradeLeg> legs = new ArrayList<>();
        for (StrategyBuilder.Leg l : built.legs()) {
            TradeLeg leg = new TradeLeg();
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
        return true;
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
    private boolean emitSignalForStrike(InstrumentSpec spec, int strike, String signalType, boolean isBullish, double spotPrice,
            double finalConfidence, double modelConfidence, double agentConfidence, double rawConfidence,
            boolean modelReady, double modelWeight, ConfidenceEngine.RawConfidenceResult rawResult, CriticAgent.CriticResult criticResult,
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
