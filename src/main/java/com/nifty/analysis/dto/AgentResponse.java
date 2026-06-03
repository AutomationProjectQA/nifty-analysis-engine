package com.nifty.analysis.dto;

import java.util.List;

public record AgentResponse(
    Double score,          // 0.0 to 100.0
    String bias,           // "BULLISH", "BEARISH", "NEUTRAL"
    List<String> comments  // Explanations for the agent's decision
) {}
