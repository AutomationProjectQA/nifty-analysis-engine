package com.nifty.analysis;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.service.OrderExecutionService;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;

public class OrderExecutionTestRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Angel One Order Execution Verification Runner ===");

        // 1. Parse credentials from application.yml
        String apiKey = "";
        String clientCode = "";
        String password = "";
        String totpKey = "";
        String telegramToken = "";
        String telegramChatId = "";

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
            }
        }

        System.out.println("Parsed Client Code: " + clientCode);
        System.out.println("Parsed API Key: " + apiKey);
        System.out.println("Parsed Telegram Bot: " + (telegramToken.isEmpty() ? "MISSING" : "****"));
        System.out.println("Parsed Telegram Chat ID: " + telegramChatId);

        if (apiKey.isEmpty() || clientCode.isEmpty() || password.isEmpty() || totpKey.isEmpty()) {
            System.err.println("Error: Credentials could not be parsed from application.yml!");
            return;
        }

        // 2. Setup Clients & Services
        WebClient.Builder webClientBuilder = WebClient.builder();
        AngelOneDataClient client = new AngelOneDataClient(webClientBuilder);
        
        setField(client, "apiKey", apiKey);
        setField(client, "clientCode", clientCode);
        setField(client, "password", password);
        setField(client, "totpKey", totpKey);

        System.out.println("Initializing client & downloading scrip master...");
        client.init();
        
        // Wait 10 seconds for the scrip master background thread to complete
        System.out.print("Downloading scrip master");
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            System.out.print(".");
        }
        System.out.println(" Done!");

        TelegramBotService telegramBotService = new TelegramBotService(webClientBuilder);
        setField(telegramBotService, "enabled", true);
        setField(telegramBotService, "botToken", telegramToken);
        setField(telegramBotService, "chatId", telegramChatId);

        OrderExecutionService executionService = new OrderExecutionService(client, webClientBuilder, telegramBotService);
        setField(executionService, "enabled", true);
        setField(executionService, "lotSize", 65);
        setField(executionService, "riskPerTradePercent", 100.0);

        // 3. Trigger Test Order Placement (Simulation or Live depending on API credentials)
        System.out.println("\n--- Triggering Test Order Execution ---");
        // Spot price at 23500. It will find ATM Option 23500 CE, fetch its LTP, check balance, and run order placement.
        try {
            executionService.executeOrder("BUY_CE", 23500, 23500.0);
            System.out.println("Order execution triggered successfully! Check console output / Telegram alerts.");
        } catch (Exception e) {
            System.err.println("Exception during test order execution:");
            e.printStackTrace();
        }

        System.out.println("\n=== Verification Complete ===");
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
