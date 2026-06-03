package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class EventRiskAgent {

    @Value("${nifty.events.block-trading-on-event:true}")
    private boolean blockTradingOnEvent;

    public AgentResponse evaluateCurrentRisk() {
        return evaluateCurrentRisk(LocalDate.now());
    }

    public AgentResponse evaluateCurrentRisk(LocalDate evaluationDate) {
        List<String> comments = new ArrayList<>();
        
        // Define high-risk macroeconomic dates for the year 2026 (simulated calendar)
        // In real setups, this would fetch from an economic calendar database or API
        List<LocalDate> eventDates = List.of(
            LocalDate.of(2026, 6, 4),   // RBI Interest Rate Decision (tomorrow in local time)
            LocalDate.of(2026, 6, 12),  // US Inflation CPI Data release
            LocalDate.of(2026, 6, 18)   // US Fed Interest Rate Decision
        );

        boolean isEventToday = eventDates.contains(evaluationDate);
        boolean isEventTomorrow = eventDates.contains(evaluationDate.plusDays(1));

        double score = 100.0;
        String bias = "NEUTRAL"; // Neutral means "Clear of extreme event risk"

        if (isEventToday) {
            score = 20.0;
            bias = "EVENT_DRIVEN";
            comments.add("CRITICAL: Major macroeconomic event scheduled for today!");
            if (blockTradingOnEvent) {
                comments.add("TRADING SUSPENDED: Avoid option buying during major events to prevent IV crush.");
            }
        } else if (isEventTomorrow) {
            score = 60.0;
            bias = "HIGH_VOLATILITY";
            comments.add("WARNING: Major macroeconomic event scheduled for tomorrow (expect pre-event volatility/IV spike)");
        } else {
            comments.add("No high-risk scheduled events found in the near-term calendar");
        }

        return new AgentResponse(score, bias, comments);
    }
}
