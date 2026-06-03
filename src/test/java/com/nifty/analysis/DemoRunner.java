package com.nifty.analysis;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.service.LlmService;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.*;

public class DemoRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println("            NIFTY OPTION SIGNAL ENGINE - END-TO-END DEMO         ");
        System.out.println("=================================================================");

        // 1. Parse credentials from application.yml
        String apiKey = "";
        String clientCode = "";
        String password = "";
        String totpKey = "";
        String telegramToken = "";
        String telegramChatId = "";
        String geminiApiKey = "";

        try (BufferedReader br = new BufferedReader(new FileReader("src/main/resources/application.yml"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("api-key:")) apiKey = extractValue(line);
                else if (line.startsWith("client-code:")) clientCode = extractValue(line);
                else if (line.startsWith("password:")) password = extractValue(line);
                else if (line.startsWith("totp-key:")) totpKey = extractValue(line);
                else if (line.startsWith("bot-token:")) telegramToken = extractValue(line);
                else if (line.startsWith("chat-id:")) telegramChatId = extractValue(line);
                else if (line.startsWith("gemini-api-key:")) geminiApiKey = extractValue(line);
            }
        }

        System.out.println("[Config] Parsed Client Code: " + clientCode);
        System.out.println("[Config] Parsed Telegram Bot: " + (telegramToken.isEmpty() ? "MISSING" : "****"));
        System.out.println("[Config] Parsed Telegram Chat: " + telegramChatId);
        System.out.println("[Config] Parsed Gemini API Key: " + (geminiApiKey.isEmpty() ? "MISSING" : "****"));

        if (apiKey.isEmpty() || clientCode.isEmpty() || password.isEmpty() || totpKey.isEmpty()) {
            System.err.println("Error: Angel One credentials could not be parsed from application.yml!");
            return;
        }

        // 2. Initialize Angel One client
        WebClient.Builder webClientBuilder = WebClient.builder();
        AngelOneDataClient client = new AngelOneDataClient(webClientBuilder);
        setField(client, "apiKey", apiKey);
        setField(client, "clientCode", clientCode);
        setField(client, "password", password);
        setField(client, "totpKey", totpKey);

        System.out.println("\n[AngelOne] Initializing client and fetching scrip master...");
        client.init();
        
        // Wait 8 seconds for background thread to load scrips
        for (int i = 0; i < 8; i++) {
            Thread.sleep(1000);
            System.out.print(".");
        }
        System.out.println(" Done!");

        // 3. Fetch Live Market Data
        System.out.println("\n[AngelOne] Fetching Live Market Data...");
        MarketSnapshotDto marketData = client.fetchMarketData();
        double spot = marketData.niftySpot();
        double futures = marketData.niftyFuture();
        double vix = marketData.indiaVix();
        System.out.println("  Nifty Spot: " + spot);
        System.out.println("  Nifty Future: " + futures);
        System.out.println("  India VIX: " + vix);

        // 4. Fetch Live Option Chain
        System.out.println("\n[AngelOne] Fetching Live Option Chain...");
        List<OptionSnapshotDto> optionChain = client.fetchOptionChain();
        System.out.println("  Strikes Returned: " + optionChain.size());

        if (optionChain.isEmpty()) {
            System.err.println("Error: Option chain is empty! Check credentials / market connection.");
            return;
        }

        // 5. Calculate Option Chain PCR and ATM Max Pain
        double totalCeOi = 0;
        double totalPeOi = 0;
        for (OptionSnapshotDto strike : optionChain) {
            totalCeOi += strike.ceOi();
            totalPeOi += strike.peOi();
        }
        double overallPcr = totalCeOi > 0 ? (totalPeOi / totalCeOi) : 1.0;
        overallPcr = Math.round(overallPcr * 100.0) / 100.0;
        System.out.println("  Calculated Overall Option PCR: " + overallPcr);

        // Print nearby strikes
        int atmStrike = ((int) Math.round(spot / 50.0)) * 50;
        System.out.println("  ATM Strike: " + atmStrike);
        System.out.println("  Sample Nearby Strikes:");
        optionChain.stream()
                .filter(o -> Math.abs(o.strikePrice() - atmStrike) <= 150)
                .forEach(o -> System.out.println("    Strike: " + o.strikePrice() + " | CE OI: " + o.ceOi() + " | PE OI: " + o.peOi() + " | PCR: " + o.pcr()));

        // 6. Simulate Engine Agents Evaluations
        System.out.println("\n[Engine] Simulating Agent Analyses...");
        
        // Technical indicators (EMA alignment, RSI value, spot relative to VWAP)
        double ema20 = spot - 25.0; // Bullish setup: spot > ema20 > ema50
        double ema50 = spot - 50.0;
        double rsi = 62.5;         // Strong momentum zone (55 - 68)
        double vwap = spot - 15.0;  // Spot above VWAP

        System.out.println("  EMA 20/50: Bullish Structure (Spot > EMA20 > EMA50)");
        System.out.println("  RSI: 62.5 (Bullish Momentum)");
        System.out.println("  VWAP: Spot Consolidated Above VWAP (" + vwap + ")");

        // Compute Raw Confidence
        double trendScore = 85.0;   // Trending Bullish
        double rsiScore = 100.0;    // Bullish RSI
        double vwapScore = 100.0;   // Above VWAP
        double pcrScore = overallPcr >= 1.1 ? 100.0 : (overallPcr >= 0.8 ? 50.0 : 0.0);
        double oiScore = overallPcr >= 1.1 ? 85.0 : 50.0;
        double futurePremium = futures - spot;
        double futuresScore = futurePremium > 35.0 ? 100.0 : (futurePremium > 15.0 ? 50.0 : 0.0);
        double sentimentScore = 75.0; // Bullish sentiment

        // Default weights: Trend=20, OI=20, PCR=15, VWAP=15, RSI=10, Futures=10, Sentiment=10
        double weightedSum = (trendScore * 20.0) + (oiScore * 20.0) + (pcrScore * 15.0) + 
                            (vwapScore * 15.0) + (rsiScore * 10.0) + (futuresScore * 10.0) + 
                            (sentimentScore * 10.0);
        double rawConfidence = weightedSum / 100.0;
        
        // Critic Agent deductions
        double finalConfidence = rawConfidence;
        List<String> penaltiesApplied = new ArrayList<>();
        if (vix > 18.0) {
            finalConfidence -= 10.0;
            penaltiesApplied.add("High volatility penalty (-10%) due to VIX=" + vix);
        }
        if (rsi > 70.0) {
            finalConfidence -= 5.0;
            penaltiesApplied.add("Overbought RSI penalty (-5%)");
        }

        System.out.println("  Calculated Raw Confidence: " + rawConfidence + "%");
        System.out.println("  Critic Penalties: " + (penaltiesApplied.isEmpty() ? "None" : penaltiesApplied));
        System.out.println("  Final Adjusted Confidence: " + finalConfidence + "%");

        boolean triggerSignal = finalConfidence >= 80.0;
        System.out.println("  Trade Signal Triggered: " + (triggerSignal ? "YES (>=80%)" : "NO (<80%)"));

        // Force trigger signal for the demo to show Gemini and Telegram output
        System.out.println("\n[Engine] Forcing signal trigger for demonstration purposes...");
        String signalType = "BUY_CE";
        double entry = 150.0;
        double stopLoss = 135.0;
        double target1 = 165.0;
        double target2 = 180.0;

        TradeSignal signal = new TradeSignal();
        signal.setSignalTime(LocalDateTime.now());
        signal.setSignalType(signalType);
        signal.setStrike(atmStrike);
        signal.setEntry(entry);
        signal.setStopLoss(stopLoss);
        signal.setTarget1(target1);
        signal.setTarget2(target2);
        signal.setConfidence(finalConfidence);
        signal.setStatus("DEMO_ACTIVE");

        // 7. Generate Live Gemini Analysis
        System.out.println("\n[Gemini] Calling Gemini 1.5 Flash API to generate trade thesis explanation...");
        LlmService llmService = new LlmService(webClientBuilder);
        setField(llmService, "geminiApiKey", geminiApiKey);

        Map<String, Double> scores = Map.of(
            "Trend", trendScore,
            "OI", oiScore,
            "PCR", pcrScore,
            "VWAP", vwapScore,
            "RSI", rsiScore,
            "Futures", futuresScore,
            "Sentiment", sentimentScore
        );
        String explanation = llmService.generateTradeExplanation(
            signalType, 
            atmStrike, 
            finalConfidence, 
            scores, 
            "Live market demonstration. High option build-up on ATM. Trend is bullish."
        );
        System.out.println("-----------------------------------------------------------------");
        System.out.println("Gemini Explanation Output:\n" + explanation);
        System.out.println("-----------------------------------------------------------------");

        // 8. Dispatch Telegram Notification
        System.out.println("\n[Telegram] Dispatching formatted notification to Telegram chat...");
        TelegramBotService telegramBotService = new TelegramBotService(webClientBuilder);
        setField(telegramBotService, "enabled", true);
        setField(telegramBotService, "botToken", telegramToken);
        setField(telegramBotService, "chatId", telegramChatId);

        List<String> reasons = List.of(
            "*Thesis:* " + explanation,
            "Bullish trend structure confirmed (EMA alignment)",
            "Overall Option PCR support at " + overallPcr,
            "Breakout above daily VWAP verified"
        );

        telegramBotService.sendSignal(signal, reasons);
        
        // Wait 3 seconds for async telegram call to log response
        Thread.sleep(3000);

        System.out.println("\n=================================================================");
        System.out.println("                    DEMO EXECUTION COMPLETE                      ");
        System.out.println("=================================================================");
        System.exit(0);
    }

    private static String extractValue(String line) {
        String val = line.substring(line.indexOf(":") + 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return val;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
