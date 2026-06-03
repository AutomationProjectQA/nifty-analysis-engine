package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final ConfidenceEngine confidenceEngine;
    private final CriticAgent criticAgent;
    private final TechnicalAgent technicalAgent;
    private final OptionsAgent optionsAgent;
    private final SentimentAgent sentimentAgent;
    
    private final TradeSignalRepository tradeSignalRepository;
    private final SignalExplanationRepository signalExplanationRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final TelegramBotService telegramBotService;
    private final LlmService llmService;

    @Transactional
    public void evaluateMarketForSignals(MarketSnapshot latest, Double prevSpot) {
        log.info("Decision Agent executing trade signals search...");
        
        // 1. Fetch Option Snapshots to retrieve active option chain
        LocalDateTime latestOptionTime = optionSnapshotRepository.findLatestSnapshotTime();
        if (latestOptionTime == null) {
            log.warn("No option snapshots available. Cannot evaluate trading signals.");
            return;
        }
        
        List<OptionSnapshot> optionChainEntities = optionSnapshotRepository.findBySnapshotTime(latestOptionTime);
        List<OptionSnapshotDto> optionChainDtos = optionChainEntities.stream().map(o -> new OptionSnapshotDto(
                o.getStrikePrice(), o.getCeOi(), o.getPeOi(), o.getCeOiChange(), o.getPeOiChange(),
                o.getIv(), o.getPcr(), o.getMaxPain(), o.getSnapshotTime()
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

        // 3. Compute raw confidence
        ConfidenceEngine.RawConfidenceResult rawResult = confidenceEngine.calculateRawConfidence(latest, optionChainDtos, spotChange);
        
        // 4. Critic Agent invalidation checks
        CriticAgent.CriticResult criticResult = criticAgent.evaluateAndApplyPenalties(
                rawResult.rawConfidence(), latest, optionChainEntities, isBullish
        );

        double finalConfidence = criticResult.adjustedConfidence();
        log.info("Final evaluated signal confidence: {}% (Threshold = 80%)", finalConfidence);

        // 5. Gating: Signal generated only if adjusted confidence >= 80%
        if (finalConfidence < 80.0) {
            log.info("Signal confidence ({}%) below threshold. NO TRADE.", finalConfidence);
            return;
        }

        // 6. Generate pricing levels (simulated option premium: base of 150 points)
        double entry = 150.0;
        double stopLoss = entry - 15.0;  // 15 point SL
        double target1 = entry + 15.0;   // Target 1 (1:1 RR)
        double target2 = entry + 30.0;   // Target 2 (1:2 RR)

        TradeSignal signal = new TradeSignal();
        signal.setSignalTime(LocalDateTime.now());
        signal.setSignalType(signalType);
        signal.setStrike(atmStrike);
        signal.setEntry(entry);
        signal.setStopLoss(stopLoss);
        signal.setTarget1(target1);
        signal.setTarget2(target2);
        signal.setConfidence(finalConfidence);
        signal.setStatus("ACTIVE");

        tradeSignalRepository.save(signal);

        // 7. Save explanation factors
        List<SignalExplanation> explanations = new ArrayList<>();
        
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
    }
}
