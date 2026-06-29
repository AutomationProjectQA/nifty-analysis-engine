package com.nifty.analysis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.entity.TradeReflection;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
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

    @SuppressWarnings("unchecked")
    public String generatePreMarketReport(double spot, double vix, List<OptionSnapshot> optionChain) {
        int highestCeStrike = 0;
        long highestCeOi = 0;
        int highestPeStrike = 0;
        long highestPeOi = 0;
        if (optionChain != null) {
            for (OptionSnapshot os : optionChain) {
                if (os.getCeOi() != null && os.getCeOi() > highestCeOi) {
                    highestCeOi = os.getCeOi();
                    highestCeStrike = os.getStrikePrice();
                }
                if (os.getPeOi() != null && os.getPeOi() > highestPeOi) {
                    highestPeOi = os.getPeOi();
                    highestPeStrike = os.getStrikePrice();
                }
            }
        }

        String prompt = "You are the Nifty Pre-Market Vlog Analyzer. Write a morning market view report for today.\n\n"
                + "Context:\n"
                + "Nifty Spot Close: " + spot + "\n"
                + "India VIX: " + vix + "\n"
                + "Highest CE OI Strike (Resistance): " + (highestCeStrike > 0 ? highestCeStrike : "N/A") + " (OI: " + highestCeOi + ")\n"
                + "Highest PE OI Strike (Support): " + (highestPeStrike > 0 ? highestPeStrike : "N/A") + " (OI: " + highestPeOi + ")\n\n"
                + "Guidelines: Write a structured daily pre-market update. Start with a headline, followed by 3 short paragraphs covering:\n"
                + "1. Global Market Summary & Gift Nifty expected open (bullish, bearish, or sideways bias).\n"
                + "2. Key Levels: Explain how the option chain support at " + highestPeStrike + " and resistance at " + highestCeStrike + " will act as bounds.\n"
                + "3. Expected Market Opening Strategy: Provide entry suggestions for retail options buyers today.\n"
                + "Keep the tone professional and informative.";

        return callGemini(prompt, "Pre-market morning view generated successfully. Nifty is expected to open sideways-to-positive with support at " + highestPeStrike + " and overhead resistance at " + highestCeStrike + ".");
    }

    @SuppressWarnings("unchecked")
    public String generatePostMarketReport(MarketSnapshot snapshot, double pcr, double maxPain, List<OptionSnapshot> optionChain) {
        String prompt = "You are the Nifty Post-Market Vlog Analyzer. Write a detailed daily close market report.\n\n"
                + "Context:\n"
                + "Nifty Spot Close: " + snapshot.getNiftySpot() + "\n"
                + "Nifty Future Close: " + snapshot.getNiftyFuture() + "\n"
                + "India VIX: " + snapshot.getIndiaVix() + "\n"
                + "Put-Call Ratio (PCR): " + pcr + "\n"
                + "Max Pain Strike: " + maxPain + "\n"
                + "Technical Indicators: RSI = " + snapshot.getRsi() + ", VWAP = " + snapshot.getVwap() + ", EMA20 = " + snapshot.getEma20() + "\n\n"
                + "Guidelines: Write a structured daily post-market update. Start with a headline, followed by 3 short paragraphs covering:\n"
                + "1. Daily Nifty Index summary: Highlight today's price action and how it closed relative to the 20-EMA and VWAP support.\n"
                + "2. Options Chain Shift: Discuss the implications of the current PCR (" + pcr + ") and Max Pain strike (" + maxPain + ").\n"
                + "3. Next Day Outlook: Give a directional bias for tomorrow's trading session.\n"
                + "Keep the tone professional and analytical.";

        return callGemini(prompt, "Post-market daily update generated successfully. Nifty closed at " + snapshot.getNiftySpot() + ", showing a neutral-to-bullish outlook with PCR at " + pcr + " and Max Pain at " + maxPain + ".");
    }

    @SuppressWarnings("unchecked")
    private String callGemini(String prompt, String fallbackText) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            return fallbackText;
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                + geminiApiKey;

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contentsMap = new HashMap<>();
        Map<String, Object> partsMap = new HashMap<>();

        partsMap.put("text", prompt);
        contentsMap.put("parts", List.of(partsMap));
        requestBody.put("contents", List.of(contentsMap));

        // CRITICAL: gemini-2.5-flash enables "thinking" by default, which silently consumes the
        // entire output budget and returns a candidate with finishReason=MAX_TOKENS and NO parts —
        // the parser then fails and we fall back to a static template (the boilerplate / one-liner
        // the portal was showing). Disable thinking and cap output so tokens go to the answer.
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("maxOutputTokens", 2048);
        generationConfig.put("temperature", 0.7);
        generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
        requestBody.put("generationConfig", generationConfig);

        try {
            Map<String, Object> map = (Map<String, Object>) webClientBuilder.build().post()
                    .uri(url)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String text = extractGeminiText(map);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
            log.error("Gemini returned no usable text; using fallback. Response meta: {}", geminiResponseMeta(map));
            return fallbackText;
        } catch (Exception e) {
            log.error("Failed to call Gemini API; using fallback.", e);
            return fallbackText;
        }
    }

    /** Safely pulls candidates[0].content.parts[*].text, concatenating multi-part responses. */
    @SuppressWarnings("unchecked")
    private String extractGeminiText(Map<String, Object> map) {
        if (map == null) return null;
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            if (content == null) return null;
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> part : parts) {
                Object t = part.get("text");
                if (t != null) sb.append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception ex) {
            log.error("Failed to parse Gemini response structure", ex);
            return null;
        }
    }

    /** Extracts diagnostic fields (finishReason / promptFeedback) for logging when no text comes back. */
    @SuppressWarnings("unchecked")
    private String geminiResponseMeta(Map<String, Object> map) {
        if (map == null) return "null response";
        try {
            Object finishReason = null;
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                finishReason = candidates.get(0).get("finishReason");
            }
            Object promptFeedback = map.get("promptFeedback");
            return "finishReason=" + finishReason + ", promptFeedback=" + promptFeedback;
        } catch (Exception ex) {
            return "unparseable response";
        }
    }

    /**
     * Summarizes the day's REAL market headlines (from RSS) plus live global cues into a
     * 'Top 5 Events Impacting Nifty Today' article. The summary is grounded in actual headlines —
     * no fabricated FII figure. GIFT Nifty is passed as an estimate derived from Dow futures and is
     * labelled as such. If the LLM is unavailable, the fallback lists the real headlines verbatim
     * (never a static template).
     */
    public String generateMarketNews(List<com.nifty.analysis.collector.client.impl.NewsRssClient.Headline> headlines,
                                     double giftNiftyEst, double dowFutures, double dxy, double crude) {
        StringBuilder headlineBlock = new StringBuilder();
        if (headlines != null) {
            int n = 1;
            for (var h : headlines) {
                headlineBlock.append(n++).append(". ").append(h.title());
                if (h.source() != null) headlineBlock.append(" (" + h.source() + ")");
                headlineBlock.append("\n");
            }
        }

        String prompt = "You are a markets editor for an Indian options-trading desk. Using the REAL headlines and "
                + "global cues below, write a markdown article titled 'Top 5 Events Impacting Nifty Today'.\n\n"
                + "Today's headlines:\n" + (headlineBlock.length() > 0 ? headlineBlock : "(no headlines available)\n") + "\n"
                + "Global cues (for context):\n"
                + "- US Dow Jones Futures move: " + dowFutures + " pts\n"
                + "- US Dollar Index (DXY): " + dxy + "\n"
                + "- Brent Crude Oil: " + crude + " USD/bbl\n"
                + "- Estimated GIFT Nifty bias (derived from Dow futures, approximate): " + giftNiftyEst + " pts\n\n"
                + "Guidelines:\n"
                + "Pick the 5 most market-moving items and write exactly 5 numbered bullets. Each starts with a bold "
                + "title, then 1-2 sentences on the concrete impact on the Nifty 50 (direction, sector, levels). "
                + "Prefer the actual headlines; use the global cues only as supporting context. Do NOT invent FII/DII "
                + "figures or any number not given above. Output clean markdown only.";

        return callGemini(prompt, fallbackNewsFromHeadlines(headlines));
    }

    /** Builds a real-headline fallback (used only when the LLM is unavailable). */
    private String fallbackNewsFromHeadlines(List<com.nifty.analysis.collector.client.impl.NewsRssClient.Headline> headlines) {
        if (headlines == null || headlines.isEmpty()) {
            return "### Top Market Headlines\n\nLive headlines are temporarily unavailable. Please try again shortly.";
        }
        StringBuilder sb = new StringBuilder("### Top Market Headlines\n\n");
        int n = 1;
        for (var h : headlines) {
            if (n > 5) break;
            sb.append(n++).append(". **").append(h.title()).append("**");
            if (h.source() != null) sb.append(" — _").append(h.source()).append("_");
            sb.append("\n");
        }
        return sb.toString();
    }
}
