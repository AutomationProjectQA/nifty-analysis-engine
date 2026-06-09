package com.nifty.analysis.collector.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class YahooFinanceClient {

    private final WebClient.Builder webClientBuilder;

    public record SentimentData(
            double dowFuturesPoints,
            double dollarIndex,
            double crudeOil,
            double giftNiftyPremium
    ) {}

    public SentimentData fetchLiveSentimentData() {
        log.info("Fetching live sentiment data from Yahoo Finance...");
        
        double dowPoints = 80.0; // Defaults
        double dxy = 101.5;
        double crude = 78.0;
        double giftPremium = 15.0;

        try {
            // 1. Fetch Dow Futures (YM=F)
            Map<String, Object> dowMeta = fetchMeta("YM=F");
            if (dowMeta != null) {
                double ltp = parseDouble(dowMeta.get("regularMarketPrice"));
                double prevClose = parseDouble(dowMeta.get("previousClose"));
                if (prevClose == 0.0) {
                    prevClose = parseDouble(dowMeta.get("chartPreviousClose"));
                }
                if (ltp > 0 && prevClose > 0) {
                    dowPoints = ltp - prevClose;
                    log.info("Fetched live Dow Futures: LTP={}, PrevClose={}, Points={}", ltp, prevClose, dowPoints);
                }
            }

            // 2. Fetch Dollar Index (DX-Y.NYB)
            Map<String, Object> dxyMeta = fetchMeta("DX-Y.NYB");
            if (dxyMeta != null) {
                double val = parseDouble(dxyMeta.get("regularMarketPrice"));
                if (val > 0) {
                    dxy = val;
                    log.info("Fetched live Dollar Index: {}", dxy);
                }
            }

            // 3. Fetch Brent Crude (BZ=F)
            Map<String, Object> crudeMeta = fetchMeta("BZ=F");
            if (crudeMeta != null) {
                double val = parseDouble(crudeMeta.get("regularMarketPrice"));
                if (val > 0) {
                    crude = val;
                    log.info("Fetched live Brent Crude Oil: {}", crude);
                }
            }

            // 4. Approximate GIFT Nifty premium/discount
            // Since Yahoo Finance doesn't list NSE IX GIFT Nifty futures directly,
            // we approximate the opening bias from US Dow Futures points:
            // Historically, a 100 pt move in Dow Futures correlates to roughly a 15 pt move in Nifty
            giftPremium = Math.round(dowPoints * 0.15 * 100.0) / 100.0;
            log.info("Approximated GIFT Nifty premium from Dow Futures points: {}", giftPremium);

        } catch (Exception e) {
            log.error("Failed to fetch live sentiment data from Yahoo Finance. Using default fallbacks.", e);
        }

        return new SentimentData(dowPoints, dxy, crude, giftPremium);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchMeta(String symbol) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?interval=1d&range=1d";
        try {
            Map<String, Object> response = webClientBuilder.build().get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null) {
                Map<String, Object> chart = (Map<String, Object>) response.get("chart");
                if (chart != null) {
                    List<Map<String, Object>> result = (List<Map<String, Object>>) chart.get("result");
                    if (result != null && !result.isEmpty()) {
                        return (Map<String, Object>) result.get(0).get("meta");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch quote for symbol {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    private double parseDouble(Object obj) {
        if (obj == null) return 0.0;
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
