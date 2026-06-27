package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import com.nifty.analysis.notification.TelegramBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class OrderExecutionService {

    @Value("${nifty.order-execution.enabled:false}")
    private boolean enabled;

    @Value("${nifty.order-execution.lot-size:65}")
    private int lotSize;

    // Percent of available wallet capital allocated to each order.
    @Value("${nifty.risk.capital-per-order-percent:20.0}")
    private double capitalPerOrderPercent;

    @Value("${nifty.risk.target-profit-percent:2.0}")
    private double targetProfitPercent;

    @Value("${nifty.risk.stop-loss-percent:40.0}")
    private double stopLossPercent;

    // Null when the provider is not "angelone" (e.g. simulated mode) — no broker client.
    private final AngelOneDataClient angelOneDataClient;
    private final WebClient.Builder webClientBuilder;
    private final TelegramBotService telegramBotService;

    public OrderExecutionService(@Nullable AngelOneDataClient angelOneDataClient,
                                 WebClient.Builder webClientBuilder,
                                 TelegramBotService telegramBotService) {
        this.angelOneDataClient = angelOneDataClient;
        this.webClientBuilder = webClientBuilder;
        this.telegramBotService = telegramBotService;
    }

    /**
     * Outcome of an order attempt. PLACED = a real broker order was confirmed (carries the
     * order id). SKIPPED = no real order, by design (execution disabled or simulated/paper)
     * — the signal is still tracked as paper. FAILED = a live order was attempted but did
     * not go through — the caller must NOT create a phantom ACTIVE position.
     */
    public record OrderResult(Outcome outcome, String orderId) {
        public enum Outcome { PLACED, SKIPPED, FAILED }
        public static OrderResult placed(String orderId) { return new OrderResult(Outcome.PLACED, orderId); }
        public static OrderResult skipped() { return new OrderResult(Outcome.SKIPPED, null); }
        public static OrderResult failed() { return new OrderResult(Outcome.FAILED, null); }
    }

    public OrderResult executeOrder(String signalType, int strike, double prevSpot) {
        return executeOrder(signalType, strike, prevSpot, 1);
    }

    /**
     * @param splitAcross number of ladder legs sharing this signal's capital budget.
     *                    The per-order allocation is divided by this so the whole ladder
     *                    stays within one order's budget instead of N× over-allocating.
     * @return the order outcome (PLACED / SKIPPED / FAILED).
     */
    public OrderResult executeOrder(String signalType, int strike, double prevSpot, int splitAcross) {
        if (!enabled) {
            log.info("Order execution is disabled. Skipping live order placement (paper).");
            return OrderResult.skipped();
        }
        if (angelOneDataClient == null) {
            log.info("No broker client available (simulated mode). Skipping order placement (paper).");
            return OrderResult.skipped();
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
            return OrderResult.failed();
        }

        // Fetch LTP to use as entry price. A real order must never be placed at a
        // fabricated price — if the live premium is unavailable, abort the order.
        double entryPremium = angelOneDataClient.fetchLtp(scrip.exchSeg(), scrip.token());
        if (entryPremium <= 0) {
            log.error("Live LTP unavailable for {} (token {}). Aborting order — refusing to trade at a fabricated price.",
                    symbol, scrip.token());
            telegramBotService.sendMessage(String.format(
                    "⚠️ *ORDER ABORTED*\nNo live premium for `%s` (feed down / simulated session). No order placed.", symbol));
            return OrderResult.failed();
        }

        // Fetch available wallet balance
        double walletBalance = fetchWalletBalance();
        log.info("Fetched wallet balance: {} INR", walletBalance);

        // Calculate lots and quantity based on the configured per-order allocation,
        // split across the ladder legs sharing this budget (so the ladder doesn't N×-allocate).
        double allocatedCapital = walletBalance * (capitalPerOrderPercent / 100.0) / Math.max(1, splitAcross);
        int lots = (int) Math.floor(allocatedCapital / (entryPremium * lotSize));
        int quantity = lots * lotSize;

        if (lots <= 0) {
            log.warn("Insufficient balance ({}) to buy even 1 lot (Cost: {})", walletBalance, entryPremium * lotSize);
            telegramBotService.sendMessage(String.format("⚠️ *ORDER SYSTEM ALERT: INSUFFICIENT BALANCE*\n" +
                    "Wallet Balance: `%.2f` INR\n" +
                    "Required for 1 lot: `%.2f` INR (Premium: `%.2f`, Lot Size: `%d`)",
                    walletBalance, entryPremium * lotSize, entryPremium, lotSize));
            return OrderResult.failed();
        }

        // Target value is the configured profit percent of the option price
        // (rounded to nearest 0.05 tick size)
        double rawTargetPoints = entryPremium * (targetProfitPercent / 100.0);
        double targetPoints = Math.round(rawTargetPoints * 20.0) / 20.0;
        if (targetPoints < 0.05) {
            targetPoints = 0.05;
        }

        // Stop-loss at the configured percent of the option price (rounded to nearest 0.05 tick)
        double rawStopLossPoints = entryPremium * (stopLossPercent / 100.0);
        double stopLossPoints = Math.round(rawStopLossPoints * 20.0) / 20.0;
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
                    "• *Target (%.0f%%):* `+%.2f` points (Exit: `%.2f`)\n" +
                    "• *Stop Loss (%.0f%%):* `-%.2f` points (Exit: `%.2f`)\n" +
                    "• *Allocated Wallet Capital:* `%.2f` INR",
                    symbol, scrip.token(), signalType, lots, quantity, entryPremium,
                    targetProfitPercent, targetPoints, entryPremium + targetPoints,
                    stopLossPercent, stopLossPoints, entryPremium - stopLossPoints,
                    entryPremium * quantity);
            telegramBotService.sendMessage(msg);
            return OrderResult.skipped(); // paper trade — track the signal, no real order
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
                        "• *Target (%.0f%%):* `+%.2f` points (Exit: `%.2f`)\n" +
                        "• *Stop Loss (%.0f%%):* `-%.2f` points (Exit: `%.2f`)\n" +
                        "• *Wallet Capital Used:* `%.2f` INR",
                        orderId, symbol, scrip.token(), signalType, lots, quantity, entryPremium,
                        targetProfitPercent, targetPoints, entryPremium + targetPoints,
                        stopLossPercent, stopLossPoints, entryPremium - stopLossPoints,
                        entryPremium * quantity);
                telegramBotService.sendMessage(msg);
                return OrderResult.placed(orderId);
            } else {
                String errorMsg = response != null ? (String) response.get("message") : "Empty response";
                log.error("Failed to place Robo Order: {}", errorMsg);
                telegramBotService.sendMessage(String.format("⚠️ *LIVE ORDER PLACEMENT FAILED*\n" +
                        "• *Symbol:* `%s`\n" +
                        "• *Error:* `%s`", symbol, errorMsg));
                return OrderResult.failed();
            }
        } catch (Exception e) {
            log.error("Exception during live order placement", e);
            telegramBotService.sendMessage(String.format("🚨 *ORDER PLACEMENT EXCEPTION*\n`%s`", e.getMessage()));
            return OrderResult.failed();
        }
    }

    /**
     * Computes the lot-aligned quantity for an order given the entry premium, using the
     * configured per-order capital allocation and the (real or simulated) wallet balance.
     * Returns at least one lot so that tracked P&L is never zero.
     */
    public int calculateQuantity(double entryPremium) {
        return calculateQuantity(entryPremium, 1);
    }

    /**
     * @param splitAcross number of ladder legs sharing this signal's capital budget
     *                    (the per-order allocation is divided by this).
     */
    public int calculateQuantity(double entryPremium, int splitAcross) {
        if (entryPremium <= 0) {
            return lotSize;
        }
        double walletBalance = fetchWalletBalance();
        double allocatedCapital = walletBalance * (capitalPerOrderPercent / 100.0) / Math.max(1, splitAcross);
        int lots = (int) Math.floor(allocatedCapital / (entryPremium * lotSize));
        if (lots < 1) {
            lots = 1;
        }
        return lots * lotSize;
    }

    private double fetchWalletBalance() {
        if (angelOneDataClient == null) {
            return 50000.0; // simulated mode — no broker session
        }
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
