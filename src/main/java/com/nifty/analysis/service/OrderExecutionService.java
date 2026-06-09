package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import com.nifty.analysis.notification.TelegramBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExecutionService {

    @Value("${nifty.order-execution.enabled:false}")
    private boolean enabled;

    @Value("${nifty.order-execution.lot-size:65}")
    private int lotSize;

    @Value("${nifty.order-execution.risk-per-trade-percent:100.0}")
    private double riskPerTradePercent;

    private final AngelOneDataClient angelOneDataClient;
    private final WebClient.Builder webClientBuilder;
    private final TelegramBotService telegramBotService;

    public void executeOrder(String signalType, int strike, double prevSpot) {
        if (!enabled) {
            log.info("Order execution is disabled. Skipping live order placement.");
            return;
        }

        log.info("Starting order execution workflow for signalType={}, strike={}", signalType, strike);

        String optionType = "BUY_CE".equals(signalType) ? "CE" : "PE";
        String expiryDateSymbolStr = angelOneDataClient.getExpiryDateSymbolStr();
        String symbol = "NIFTY" + expiryDateSymbolStr + strike + optionType;

        AngelOneDataClient.ScripTokenDetails scrip = angelOneDataClient.getScripDetails(symbol);
        if (scrip == null) {
            log.error("Scrip details not found for symbol: {}. Cannot place order.", symbol);
            telegramBotService.sendMessage(
                    String.format("⚠️ *ORDER EXECUTION FAILED*\nScrip details not found for `%s`.", symbol));
            return;
        }

        // Fetch LTP to use as entry price
        double entryPremium = angelOneDataClient.fetchLtp(scrip.exchSeg(), scrip.token());
        if (entryPremium <= 0) {
            entryPremium = 150.0; // Fallback
        }

        // Fetch available wallet balance
        double walletBalance = fetchWalletBalance();
        log.info("Fetched wallet balance: {} INR", walletBalance);

        // Calculate lots and quantity based on 100% allocation
        double allocatedCapital = walletBalance * (riskPerTradePercent / 100.0);
        int lots = (int) Math.floor(allocatedCapital / (entryPremium * lotSize));
        int quantity = lots * lotSize;

        if (lots <= 0) {
            log.warn("Insufficient balance ({}) to buy even 1 lot (Cost: {})", walletBalance, entryPremium * lotSize);
            telegramBotService.sendMessage(String.format("⚠️ *ORDER SYSTEM ALERT: INSUFFICIENT BALANCE*\n" +
                    "Wallet Balance: `%.2f` INR\n" +
                    "Required for 1 lot: `%.2f` INR (Premium: `%.2f`, Lot Size: `%d`)",
                    walletBalance, entryPremium * lotSize, entryPremium, lotSize));
            return;
        }

        // Target value is exactly 2% of buying option price (rounded to nearest 0.05
        // tick size)
        double rawTargetPoints = entryPremium * 0.02;
        double targetPoints = Math.round(rawTargetPoints * 20.0) / 20.0;
        if (targetPoints < 0.05) {
            targetPoints = 0.05;
        }

        // Symmetrical stop-loss at 20% (rounded to nearest 0.05 tick size)
        double rawStopLossPoints = entryPremium * 0.40;
        double stopLossPoints = Math.round(rawStopLossPoints * 40.0) / 40.0;
        if (stopLossPoints < 0.05) {
            stopLossPoints = 0.05;
        }

        String jwtToken = angelOneDataClient.getJwtToken();
        boolean isSimulation = jwtToken == null || "SIMULATED_JWT_TOKEN".equals(jwtToken);

        if (isSimulation) {
            log.info(
                    "[SIMULATION MODE] Placing Simulated Robo Order for symbol={}, qty={}, price={}, target={}, stoploss={}",
                    symbol, quantity, entryPremium, targetPoints, stopLossPoints);

            String msg = String.format("🤖 *SIMULATED ROBO ORDER PLACED*\n\n" +
                    "• *Symbol:* `%s` (Token: `%s`)\n" +
                    "• *Type:* `%s`\n" +
                    "• *Lots:* `%d` (Qty: `%d`)\n" +
                    "• *Entry Premium:* `%.2f`\n" +
                    "• *Target (2%%):* `+%.2f` points (Exit: `%.2f`)\n" +
                    "• *Stop Loss (2%%):* `-%.2f` points (Exit: `%.2f`)\n" +
                    "• *Allocated Wallet Capital:* `%.2f` INR",
                    symbol, scrip.token(), signalType, lots, quantity, entryPremium,
                    targetPoints, entryPremium + targetPoints,
                    stopLossPoints, entryPremium - stopLossPoints,
                    entryPremium * quantity);
            telegramBotService.sendMessage(msg);
            return;
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("variety", "ROBO");
            request.put("tradingsymbol", symbol);
            request.put("symboltoken", scrip.token());
            request.put("transactiontype", "BUY");
            request.put("exchange", scrip.exchSeg());
            request.put("ordertype", "LIMIT");
            request.put("producttype", "BO");
            request.put("duration", "DAY");
            request.put("price", entryPremium);
            request.put("quantity", quantity);
            request.put("squareoff", targetPoints);
            request.put("stoploss", stopLossPoints);

            log.info("Sending placeOrder request to Angel One: {}", request);

            Map<String, Object> response = webClientBuilder.build().post()
                    .uri("https://apiconnect.angelone.in/rest/secure/angelbroking/order/v1/placeOrder")
                    .header("Authorization", "Bearer " + jwtToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", "192.168.1.100")
                    .header("X-ClientPublicIP", "192.168.1.100")
                    .header("X-MACAddress", "02:00:00:00:00:00")
                    .header("X-PrivateKey", angelOneDataClient.getApiKey())
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                String orderId = data != null ? (String) data.get("orderid") : "UNKNOWN";
                log.info("Successfully placed Robo Order! Order ID: {}", orderId);

                String msg = String.format("🚀 *LIVE ROBO ORDER PLACED*\n\n" +
                        "• *Order ID:* `%s`\n" +
                        "• *Symbol:* `%s` (Token: `%s`)\n" +
                        "• *Type:* `%s`\n" +
                        "• *Lots:* `%d` (Qty: `%d`)\n" +
                        "• *Entry Premium:* `%.2f`\n" +
                        "• *Target (2%%):* `+%.2f` points (Exit: `%.2f`)\n" +
                        "• *Stop Loss (2%%):* `-%.2f` points (Exit: `%.2f`)\n" +
                        "• *Wallet Capital Used:* `%.2f` INR",
                        orderId, symbol, scrip.token(), signalType, lots, quantity, entryPremium,
                        targetPoints, entryPremium + targetPoints,
                        stopLossPoints, entryPremium - stopLossPoints,
                        entryPremium * quantity);
                telegramBotService.sendMessage(msg);
            } else {
                String errorMsg = response != null ? (String) response.get("message") : "Empty response";
                log.error("Failed to place Robo Order: {}", errorMsg);
                telegramBotService.sendMessage(String.format("⚠️ *LIVE ORDER PLACEMENT FAILED*\n" +
                        "• *Symbol:* `%s`\n" +
                        "• *Error:* `%s`", symbol, errorMsg));
            }
        } catch (Exception e) {
            log.error("Exception during live order placement", e);
            telegramBotService.sendMessage(String.format("🚨 *ORDER PLACEMENT EXCEPTION*\n`%s`", e.getMessage()));
        }
    }

    private double fetchWalletBalance() {
        String jwtToken = angelOneDataClient.getJwtToken();
        if (jwtToken == null || "SIMULATED_JWT_TOKEN".equals(jwtToken)) {
            return 50000.0; // Simulated wallet balance
        }

        try {
            Map<String, Object> response = webClientBuilder.build().get()
                    .uri("https://apiconnect.angelone.in/rest/secure/angelbroking/user/v1/getRMS")
                    .header("Authorization", "Bearer " + jwtToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", "192.168.1.100")
                    .header("X-ClientPublicIP", "192.168.1.100")
                    .header("X-MACAddress", "02:00:00:00:00:00")
                    .header("X-PrivateKey", angelOneDataClient.getApiKey())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                if (data != null) {
                    String availableCashStr = (String) data.get("availablecash");
                    if (availableCashStr != null && !availableCashStr.isEmpty()) {
                        return Double.parseDouble(availableCashStr);
                    }
                    String netStr = (String) data.get("net");
                    if (netStr != null && !netStr.isEmpty()) {
                        return Double.parseDouble(netStr);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch RMS wallet balance from Angel One API.", e);
        }

        return 50000.0; // Fallback balance if API call fails
    }
}
