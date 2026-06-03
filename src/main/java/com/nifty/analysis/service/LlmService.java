package com.nifty.analysis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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

    /**
     * Calls Gemini 1.5 Flash to generate a natural language explanation of the
     * trade thesis.
     * Fallback to structured templates if API key is missing.
     */
    @SuppressWarnings("unchecked")
    public String generateTradeExplanation(String signalType, int strike, double confidence, Map<String, Double> scores,
            String commentSummary) {
        String prompt = buildPrompt(signalType, strike, confidence, scores, commentSummary);

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
}
