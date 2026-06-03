package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SentimentAgent {

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

        // 1. Evaluate GIFT Nifty Premium (positive is bullish)
        if (giftNiftyPremium > 30.0) {
            score += 15.0;
            comments.add("GIFT Nifty premium is strong (+" + giftNiftyPremium + " pts)");
        } else if (giftNiftyPremium < -30.0) {
            score -= 15.0;
            comments.add("GIFT Nifty discount is high (" + giftNiftyPremium + " pts)");
        } else {
            comments.add("GIFT Nifty is flat");
        }

        // 2. Evaluate Dow Futures
        if (dowFuturesPoints > 150.0) {
            score += 15.0;
            comments.add("US Dow Futures are highly positive (+" + dowFuturesPoints + " pts)");
        } else if (dowFuturesPoints < -150.0) {
            score -= 15.0;
            comments.add("US Dow Futures are highly negative (" + dowFuturesPoints + " pts)");
        }

        // 3. Evaluate Dollar Index (DXY) - inverse relationship with Indian market
        if (dollarIndex > 103.5) {
            score -= 10.0;
            comments.add("Rising Dollar Index (" + dollarIndex + ") exerts emerging market pressure");
        } else if (dollarIndex < 101.0) {
            score += 10.0;
            comments.add("Weakening Dollar Index (" + dollarIndex + ") is positive for Nifty");
        }

        // 4. Evaluate Crude Oil - higher prices are negative for India (importer)
        if (crudeOil > 85.0) {
            score -= 10.0;
            comments.add("High Crude Oil price (" + crudeOil + " USD) raises inflation risk");
        } else if (crudeOil < 75.0) {
            score += 10.0;
            comments.add("Low Crude Oil price (" + crudeOil + " USD) is beneficial for fiscal health");
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
