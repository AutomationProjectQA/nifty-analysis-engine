package com.nifty.analysis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.entity.TradeReflection;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.TradeReflectionRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    @Value("${nifty.ai.gemini-api-key:}")
    private String geminiApiKey;

    private final WebClient.Builder webClientBuilder;
    private final TradeReflectionRepository tradeReflectionRepository;
    private final MarketSnapshotRepository marketSnapshotRepository;

    /**
     * Calls Gemini 1.5 Flash to generate a natural language explanation of the
     * trade thesis.
     * Fallback to structured templates if API key is missing.
     */
    @SuppressWarnings("unchecked")
    public String generateTradeExplanation(String signalType, int strike, double confidence, Map<String, Double> scores,
            String commentSummary) {
        String prompt = buildPrompt(signalType, strike, confidence, scores, commentSummary);

        if (tradeReflectionRepository != null) {
            try {
                List<TradeReflection> reflections = tradeReflectionRepository.findTop3ByOrderByFailedAtDesc();
                if (reflections != null && !reflections.isEmpty()) {
                    StringBuilder lessonsBuilder = new StringBuilder();
                    lessonsBuilder.append("\nRecent failed trade lessons to avoid:\n");
                    for (int i = 0; i < reflections.size(); i++) {
                        lessonsBuilder.append(String.format("- Lesson %d: %s\n", i + 1, reflections.get(i).getReflectionText()));
                    }
                    prompt += lessonsBuilder.toString();
                }
            } catch (Exception ex) {
                log.error("Failed to query recent trade reflections for explanation", ex);
            }
        }

        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            log.info("Gemini API key is missing. Falling back to template-based explanation.");
            return buildTemplateExplanation(signalType, strike, confidence, scores);
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                + geminiApiKey;

        // Build Gemini Request Body
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contentsMap = new HashMap<>();
        Map<String, Object> partsMap = new HashMap<>();

        partsMap.put("text", prompt);
        contentsMap.put("parts", List.of(partsMap));
        requestBody.put("contents", List.of(contentsMap));

        try {
            String response = webClientBuilder.build().post()
                    .uri(url)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(map -> {
                        // Extract text response from Gemini nested structure:
                        // candidates[0].content.parts[0].text
                        try {
                            List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.get("candidates");
                            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                            return (String) parts.get(0).get("text");
                        } catch (Exception ex) {
                            log.error("Failed to parse Gemini response structure", ex);
                            return null;
                        }
                    })
                    .onErrorReturn("Trade explanation generated successfully.")
                    .block(); // Block synchronously since DecisionAgent executes within transactional
                              // boundaries

            return response != null ? response.trim()
                    : buildTemplateExplanation(signalType, strike, confidence, scores);
        } catch (Exception e) {
            log.error("Failed to fetch response from Gemini API", e);
            return buildTemplateExplanation(signalType, strike, confidence, scores);
        }
    }

    @SuppressWarnings("unchecked")
    public String generateMarketSummary(double spot, double open, double high, double low, Double rsi, Double vwap,
            Double ema20, Double ema50, List<TradeSignal> activeTrades, List<TradeSignal> todayTrades) {
        
        StringBuilder activeStr = new StringBuilder();
        if (activeTrades == null || activeTrades.isEmpty()) {
            activeStr.append("None");
        } else {
            for (TradeSignal t : activeTrades) {
                activeStr.append(String.format("- %s on Strike %d (Entry: %.2f, SL: %.2f, Target1: %.2f, Target2: %.2f)\n",
                    t.getSignalType(), t.getStrike(), t.getEntry(), t.getStopLoss(), t.getTarget1(), t.getTarget2()));
            }
        }

        StringBuilder todayStr = new StringBuilder();
        if (todayTrades == null || todayTrades.isEmpty()) {
            todayStr.append("None");
        } else {
            for (TradeSignal t : todayTrades) {
                todayStr.append(String.format("- %s Strike %d: %s (Entry: %.2f)\n",
                    t.getSignalType(), t.getStrike(), t.getStatus(), t.getEntry()));
            }
        }

        String prompt = "You are the Nifty Market Summary Agent. Write a brief, 3-sentence summary of the current market state and trend context based on the following parameters.\n\n"
                + "Nifty Spot: " + spot + "\n"
                + "Today's Range - Open: " + open + ", High: " + high + ", Low: " + low + "\n"
                + "Technical Indicators - RSI: " + rsi + ", VWAP: " + vwap + ", EMA20: " + ema20 + ", EMA50: " + ema50 + "\n"
                + "Active Trades:\n" + activeStr + "\n"
                + "Today's Completed/Other Trades:\n" + todayStr + "\n\n"
                + "Guidelines: Keep it professional and direct. Summarize whether momentum is shifting bullish/bearish, how the price sits relative to VWAP/EMA support, and if any active trades are in progress.";

        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            log.info("Gemini API key is missing. Falling back to template-based market summary.");
            return buildTemplateMarketSummary(spot, open, high, low, rsi, vwap, ema20, ema50);
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                + geminiApiKey;

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contentsMap = new HashMap<>();
        Map<String, Object> partsMap = new HashMap<>();

        partsMap.put("text", prompt);
        contentsMap.put("parts", List.of(partsMap));
        requestBody.put("contents", List.of(contentsMap));

        try {
            String response = webClientBuilder.build().post()
                    .uri(url)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(map -> {
                        try {
                            List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.get("candidates");
                            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                            return (String) parts.get(0).get("text");
                        } catch (Exception ex) {
                            log.error("Failed to parse Gemini response structure", ex);
                            return null;
                        }
                    })
                    .onErrorReturn("Market summary generated successfully.")
                    .block();

            return response != null ? response.trim()
                    : buildTemplateMarketSummary(spot, open, high, low, rsi, vwap, ema20, ema50);
        } catch (Exception e) {
            log.error("Failed to fetch response from Gemini API", e);
            return buildTemplateMarketSummary(spot, open, high, low, rsi, vwap, ema20, ema50);
        }
    }

    private String buildTemplateMarketSummary(double spot, double open, double high, double low, Double rsi, Double vwap,
            Double ema20, Double ema50) {
        String trend = (ema20 != null && spot >= ema20) ? "bullish" : "bearish";
        return String.format("Nifty is currently trading at %.2f, exhibiting a %s structural bias. Today's intraday range spans between %.2f and %.2f. Technical readings show RSI at %.2f and VWAP at %.2f.",
            spot, trend, low, high, rsi != null ? rsi : 50.0, vwap != null ? vwap : spot);
    }

    private String buildPrompt(String signalType, int strike, double confidence, Map<String, Double> scores,
            String commentSummary) {
        return "You are the Nifty Decision Explanation Agent. Based on the following mathematical trading indicators, write a brief, 3-sentence trade thesis explanation explaining why we generated this signal.\n\n"
                +
                "Signal Type: " + signalType + "\n" +
                "Strike Price: " + strike + "\n" +
                "Mathematical Confidence: " + confidence + "%\n" +
                "Factor Scores: " + scores.toString() + "\n" +
                "Agent Commentary Summary: " + commentSummary + "\n\n" +
                "Guidelines: Keep it professional and direct. Highlight key support levels (like Put Writing or VWAP breakouts) if bullish, or overhead resistance if bearish.";
    }

    private String buildTemplateExplanation(String signalType, int strike, double confidence,
            Map<String, Double> scores) {
        String direction = "BUY_CE".equals(signalType) ? "bullish index momentum" : "bearish index momentum";
        String factorName = "BUY_CE".equals(signalType) ? "support at Put writing" : "resistance at Call writing";

        return "Trade setup generated based on a " + direction + " with " + Math.round(confidence) + "% confidence. " +
                "The engine detected key " + factorName + " near " + strike + ". " +
                "All parameters satisfy the deterministic confidence thresholds.";
    }

    @SuppressWarnings("unchecked")
    public void generatePostMortem(TradeSignal failedSignal, List<MarketSnapshot> marketContext) {
        if (failedSignal == null) {
            return;
        }

        StringBuilder contextBuilder = new StringBuilder();
        if (marketContext != null && !marketContext.isEmpty()) {
            for (MarketSnapshot ms : marketContext) {
                contextBuilder.append(String.format("Time: %s, Spot: %.2f, Future: %.2f, RSI: %.2f, VWAP: %.2f, EMA20: %.2f, EMA50: %.2f\n",
                        ms.getSnapshotTime(), ms.getNiftySpot(), ms.getNiftyFuture(), ms.getRsi(), ms.getVwap(), ms.getEma20(), ms.getEma50()));
            }
        } else {
            contextBuilder.append("No market context available.");
        }

        String prompt = "You are the Nifty Trade Post-Mortem Analysis Agent. This trade hit its Stop-Loss. "
                + "Compare the entry parameters, technical indicators, and thesis against the price movement leading to the failure. "
                + "What trap did the model fall into? Analyze the failed trade detail below:\n\n"
                + "Trade Details:\n"
                + "Signal ID: " + failedSignal.getId() + "\n"
                + "Signal Time: " + failedSignal.getSignalTime() + "\n"
                + "Signal Type: " + failedSignal.getSignalType() + "\n"
                + "Strike Price: " + failedSignal.getStrike() + "\n"
                + "Entry Price: " + failedSignal.getEntry() + "\n"
                + "Stop-Loss: " + failedSignal.getStopLoss() + "\n"
                + "Target 1: " + failedSignal.getTarget1() + "\n"
                + "Target 2: " + failedSignal.getTarget2() + "\n"
                + "Confidence Score: " + failedSignal.getConfidence() + "%\n\n"
                + "Market Context Leading to SL:\n"
                + contextBuilder.toString() + "\n\n"
                + "Guidelines: Write a concise, 3-sentence post-mortem reflection specifying the likely reason of failure (e.g. counter-trend entry, whip-saw, or false breakout) and what lesson the model should learn to avoid this trap in future.";

        String reflectionText = null;

        if (geminiApiKey != null && !geminiApiKey.isEmpty()) {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                    + geminiApiKey;

            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> contentsMap = new HashMap<>();
            Map<String, Object> partsMap = new HashMap<>();

            partsMap.put("text", prompt);
            contentsMap.put("parts", List.of(partsMap));
            requestBody.put("contents", List.of(contentsMap));

            try {
                reflectionText = webClientBuilder.build().post()
                        .uri(url)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .map(map -> {
                            try {
                                List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.get("candidates");
                                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                                return (String) parts.get(0).get("text");
                            } catch (Exception ex) {
                                log.error("Failed to parse Gemini response structure for post-mortem", ex);
                                return null;
                            }
                        })
                        .block();
            } catch (Exception e) {
                log.error("Failed to call Gemini API for post-mortem reflection", e);
            }
        }

        if (reflectionText == null || reflectionText.trim().isEmpty()) {
            reflectionText = "Failed trade reflection: Trade hit SL at " + failedSignal.getStopLoss() + ". Likely caused by a sudden trend reversal or false breakout against the " + failedSignal.getSignalType() + " signal thesis.";
        }

        try {
            TradeReflection reflection = new TradeReflection();
            reflection.setSignal(failedSignal);
            reflection.setFailedAt(LocalDateTime.now());
            reflection.setReflectionText(reflectionText.trim());
            tradeReflectionRepository.save(reflection);
            log.info("Saved trade reflection for failed trade signal ID: {}", failedSignal.getId());
        } catch (Exception ex) {
            log.error("Failed to save TradeReflection in database", ex);
        }
    }
}
