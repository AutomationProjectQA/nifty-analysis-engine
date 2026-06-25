package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the live traded premium (LTP) of an ATM option for a given signal.
 * Returns {@code -1.0} when a live price is unavailable (e.g. simulation mode or
 * no broker session), so callers can apply their own fallback.
 */
@Service
@Slf4j
public class OptionPricingService {

    // Null when the provider is not "angelone" (e.g. simulated mode) — no broker client.
    private final AngelOneDataClient angelOneDataClient;

    public OptionPricingService(@Nullable AngelOneDataClient angelOneDataClient) {
        this.angelOneDataClient = angelOneDataClient;
    }

    /**
     * @param signalType "BUY_CE" or "BUY_PE"
     * @param strike     the option strike
     * @return live LTP, or -1.0 if it cannot be fetched
     */
    public double getOptionLtp(String signalType, int strike) {
        if (angelOneDataClient == null) {
            return -1.0; // simulated mode / no broker session
        }
        try {
            String optionType = "BUY_CE".equals(signalType) ? "CE" : "PE";
            String symbol = "NIFTY" + angelOneDataClient.getExpiryDateSymbolStr() + strike + optionType;

            AngelOneDataClient.ScripTokenDetails scrip = angelOneDataClient.getScripDetails(symbol);
            if (scrip == null) {
                log.debug("No scrip details for {} — option LTP unavailable.", symbol);
                return -1.0;
            }

            double ltp = angelOneDataClient.fetchLtp(scrip.exchSeg(), scrip.token());
            return ltp > 0 ? ltp : -1.0;
        } catch (Exception e) {
            log.warn("Failed to fetch option LTP for {} {}: {}", signalType, strike, e.getMessage());
            return -1.0;
        }
    }
}
