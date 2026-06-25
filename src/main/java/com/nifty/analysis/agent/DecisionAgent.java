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

    private final ConfidenceEngine confidenceEngine;
    private final CriticAgent criticAgent;
    private final TechnicalAgent technicalAgent;
    private final OptionsAgent optionsAgent;
    private final SentimentAgent sentimentAgent;

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

        // 1. Fetch Option Snapshots to retrieve active option chain
        LocalDateTime latestOptionTime = optionSnapshotRepository.findLatestSnapshotTime();
        if (latestOptionTime == null) {
            log.warn("No option snapshots available. Cannot evaluate trading signals.");
            return;
        }
        
        List<OptionSnapshot> optionChainEntities = optionSnapshotRepository.findBySnapshotTime(latestOptionTime);
        
        // 1.5. Gate trades if market regime is sideways to avoid Theta decay
        AgentResponse regimeResponse = marketRegimeAgent.analyze(latest.getSnapshotTime());
        if ("SIDEWAYS".equals(regimeResponse.bias())) {
            log.info("Market regime is Sideways. Skipping trade evaluation to avoid Theta decay.");
            return;
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

        // Check for existing active duplicate signals for same strike and type to avoid redundant alerts
        java.util.Optional<TradeSignal> activeSignal = tradeSignalRepository.findFirstByStrikeAndSignalTypeAndStatus(atmStrike, signalType, "ACTIVE");
        if (activeSignal.isPresent()) {
            log.info("Active signal already exists for strike {} and type {}. Skipping new signal generation.", atmStrike, signalType);
            return;
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
        log.info("Final evaluated signal confidence: {}% (Threshold = {}%)", finalConfidence, gatingThreshold);

        // 5. Gating: Signal generated only if adjusted confidence >= gatingThreshold
        if (finalConfidence < gatingThreshold) {
            log.info("Signal confidence ({}%) below threshold. NO TRADE.", finalConfidence);
            return;
        }

        // 6. Generate pricing levels from the real option premium (LTP).
        double entry = optionPricingService.getOptionLtp(signalType, atmStrike);
        if (entry <= 0) {
            entry = 150.0; // fallback when live LTP is unavailable (simulation / no broker session)
            log.info("Live option LTP unavailable for {} {}. Using fallback entry premium {}.", signalType, atmStrike, entry);
        }
        double target1 = round2(entry * (1.0 + targetProfitPercent / 200.0)); // mid-point (half the target)
        double target2 = round2(entry * (1.0 + targetProfitPercent / 100.0)); // primary profit target (e.g. +2%)
        double stopLoss = round2(entry * (1.0 - stopLossPercent / 100.0));
        int quantity = orderExecutionService.calculateQuantity(entry);

        TradeSignal signal = new TradeSignal();
        signal.setSignalTime(LocalDateTime.now());
        signal.setSignalType(signalType);
        signal.setStrike(atmStrike);
        signal.setEntry(round2(entry));
        signal.setQuantity(quantity);
        signal.setStopLoss(stopLoss);
        signal.setTarget1(target1);
        signal.setTarget2(target2);
        signal.setConfidence(finalConfidence);
        signal.setStatus("ACTIVE");

        tradeSignalRepository.save(signal);

        // 7. Save explanation factors
        List<SignalExplanation> explanations = new ArrayList<>();

        // Decision provenance: capture how the final confidence was derived so every
        // signal is fully auditable (ONNX vs rule-based agent vs blend vs final).
        explanations.add(explanation("Model_ONNX", round2(modelConfidence),
                "ONNX model directional confidence" + (modelReady ? "" : " (NOT used — model not ready)")));
        explanations.add(explanation("Agent_Weighted", round2(agentConfidence),
                "Rule-based multi-agent weighted confidence"));
        explanations.add(explanation("Blended_Raw", rawConfidence,
                modelReady
                        ? String.format("Blend: %.2f*ONNX + %.2f*Agent", modelWeight, 1.0 - modelWeight)
                        : "Agent-only (ONNX not ready)"));
        explanations.add(explanation("Final_Confidence", round2(finalConfidence),
                String.format("After critic penalties; gating threshold = %.1f%%", gatingThreshold)));
        for (SignalExplanation e : explanations) {
            e.setSignal(signal);
        }

        // Save raw weight components
        for (Map.Entry<String, Double> entryScore : rawResult.factorScores().entrySet()) {
            SignalExplanation exp = new SignalExplanation();
            exp.setSignal(signal);
            exp.setFactor(entryScore.getKey());
            exp.setScore(entryScore.getValue());
            exp.setComment("Factor raw score = " + entryScore.getValue());
            explanations.add(exp);
        }

        // Save critic penalties
        for (CriticAgent.PenaltyDetails penalty : criticResult.appliedPenalties()) {
            SignalExplanation exp = new SignalExplanation();
            exp.setSignal(signal);
            exp.setFactor(penalty.factor());
            exp.setScore(penalty.scoreAdjustment());
            exp.setComment(penalty.comment());
            explanations.add(exp);
        }

        signalExplanationRepository.saveAll(explanations);
        log.info("Saved trade signal (id={}) and {} scoring explanations.", signal.getId(), explanations.size());

        // Generate natural language trade explanation from LLM
        StringBuilder criticSummary = new StringBuilder();
        for (CriticAgent.PenaltyDetails p : criticResult.appliedPenalties()) {
            criticSummary.append(p.comment()).append("; ");
        }
        String explanation = llmService.generateTradeExplanation(
                signalType, 
                atmStrike, 
                finalConfidence, 
                rawResult.factorScores(), 
                criticSummary.toString()
        );
        
        signal.setThesis(explanation);
        tradeSignalRepository.save(signal);

        // 8. Notify via Telegram Bot
        List<String> reasons = new ArrayList<>();
        reasons.add("*Thesis:* " + explanation);
        reasons.add(isBullish ? "Bullish trend structure" : "Bearish trend structure");
        reasons.add("PCR bias is " + (isBullish ? "Supportive" : "Resistant"));
        reasons.add("VWAP Breakout verified");
        for (CriticAgent.PenaltyDetails p : criticResult.appliedPenalties()) {
            reasons.add("Critic Penalty: " + p.comment());
        }

        telegramBotService.sendSignal(signal, reasons);

        // 9. Execute Order via Angel One
        orderExecutionService.executeOrder(signalType, atmStrike, spotPrice);
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
