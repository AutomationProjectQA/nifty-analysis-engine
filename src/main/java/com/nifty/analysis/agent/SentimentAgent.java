package com.nifty.analysis.agent;

import com.nifty.analysis.collector.client.impl.YahooFinanceClient;
import com.nifty.analysis.dto.AgentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SentimentAgent {

    private final YahooFinanceClient yahooFinanceClient;

    @Value("${nifty.sentiment.gift-nifty-premium:15.0}")
    private double giftNiftyPremium;

    @Value("${nifty.sentiment.dow-futures:80.0}")
    private double dowFuturesPoints;

    @Value("${nifty.sentiment.dollar-index:101.5}")
    private double dollarIndex;

    @Value("${nifty.sentiment.crude-oil:78.0}")
    private double crudeOil;

    @Value("${nifty.sentiment.fii-net-cr:450.0}")
    private double fiiNetCr;

    public AgentResponse analyze() {
        List<String> comments = new ArrayList<>();
        double score = 50.0; // Start neutral

        double currentGiftNiftyPremium = giftNiftyPremium;
        double currentDowFuturesPoints = dowFuturesPoints;
        double currentDollarIndex = dollarIndex;
        double currentCrudeOil = crudeOil;

        if (yahooFinanceClient != null) {
            try {
                YahooFinanceClient.SentimentData liveData = yahooFinanceClient.fetchLiveSentimentData();
                currentGiftNiftyPremium = liveData.giftNiftyPremium();
                currentDowFuturesPoints = liveData.dowFuturesPoints();
                currentDollarIndex = liveData.dollarIndex();
                currentCrudeOil = liveData.crudeOil();
                comments.add("Live global sentiment data loaded successfully from Yahoo Finance");
            } catch (Exception e) {
                comments.add("Failed to fetch live sentiment data; using configuration fallbacks");
            }
        } else {
            comments.add("YahooFinanceClient is not initialized; using configuration fallbacks");
        }

        // 1. Evaluate GIFT Nifty Premium (positive is bullish)
        if (currentGiftNiftyPremium > 30.0) {
            score += 15.0;
            comments.add("GIFT Nifty premium is strong (+" + currentGiftNiftyPremium + " pts)");
        } else if (currentGiftNiftyPremium < -30.0) {
            score -= 15.0;
            comments.add("GIFT Nifty discount is high (" + currentGiftNiftyPremium + " pts)");
        } else {
            comments.add("GIFT Nifty is flat");
        }

        // 2. Evaluate Dow Futures
        if (currentDowFuturesPoints > 150.0) {
            score += 15.0;
            comments.add("US Dow Futures are highly positive (+" + currentDowFuturesPoints + " pts)");
        } else if (currentDowFuturesPoints < -150.0) {
            score -= 15.0;
            comments.add("US Dow Futures are highly negative (" + currentDowFuturesPoints + " pts)");
        }

        // 3. Evaluate Dollar Index (DXY) - inverse relationship with Indian market
        if (currentDollarIndex > 103.5) {
            score -= 10.0;
            comments.add("Rising Dollar Index (" + currentDollarIndex + ") exerts emerging market pressure");
        } else if (currentDollarIndex < 101.0) {
            score += 10.0;
            comments.add("Weakening Dollar Index (" + currentDollarIndex + ") is positive for Nifty");
        }

        // 4. Evaluate Crude Oil - higher prices are negative for India (importer)
        if (currentCrudeOil > 85.0) {
            score -= 10.0;
            comments.add("High Crude Oil price (" + currentCrudeOil + " USD) raises inflation risk");
        } else if (currentCrudeOil < 75.0) {
            score += 10.0;
            comments.add("Low Crude Oil price (" + currentCrudeOil + " USD) is beneficial for fiscal health");
        }

        // 5. Evaluate FII net flows
        if (fiiNetCr > 1000.0) {
            score += 15.0;
            comments.add("Foreign Institutional Investors (FII) are heavy buyers (+" + fiiNetCr + " Cr)");
        } else if (fiiNetCr < -1000.0) {
            score -= 15.0;
            comments.add("FIIs are heavy net sellers (" + fiiNetCr + " Cr)");
        }

        // Bound score between 0 and 100
        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : (score <= 40.0 ? "BEARISH" : "NEUTRAL");

        return new AgentResponse(score, bias, comments);
    }
}
