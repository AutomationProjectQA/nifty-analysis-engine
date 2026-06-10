# Nifty Option Buying Signal Engine - Architecture Diagram

Below is the complete architectural workflow of the system, illustrating how live data is collected, parsed, analyzed by specialized agents, scored under a dual confidence/critic model, explained by Gemini AI, and dispatched to your Telegram channel.

```mermaid
graph TD
    %% 1. Ingestion Layer
    subgraph Ingestion ["1. Data Ingestion Layer"]
        A1["Angel One SmartAPI (Live)"] -->|API Quote / Option Chain| B["Market Ingestion Client"]
        A2["SimulatedDataClient (Test)"] -->|Simulated Feeds| B
    end

    %% 2. Processing & Caching Layer
    subgraph IngestionService ["2. Data Processing & Caching"]
        B -->|Raw DTOs| C["MarketCollectorService"]
        C -->|Raw Spot/Premium| D["TechnicalIndicatorService (EMA, RSI, VWAP)"]
        C -->|Option Chains| E["OptionsIndicatorService (PCR, Max Pain, Build-ups)"]
        D & E -->|Enriched Snapshot| F["Database & Caching Layer"]
        F -->|JSON Caching| G["Redis Cache (Port 6379)"]
        F -->|Relational Persistence| H["PostgreSQL DB (Port 5432)"]
    end

    %% 3. Analytical Agent Layer
    subgraph AgentLayer ["3. Multi-Agent Analysis"]
        H -->|Latest Snapshot & Candles| I["DecisionAgent (Orchestrator)"]
        I -->|Extract Normalized Features| J["TechnicalAgent"]
        I -->|Evaluate Open Interest| K["OptionsAgent"]
        I -->|Evaluate Macro Sentiment| L["SentimentAgent"]
        I -->|Evaluate Volatility & VIX| M["MarketRegimeAgent"]
    end

    %% 4. Scoring & Gating Engines
    subgraph ScoringEngine ["4. Dual Engine Evaluation"]
        J -->|Normalized Features| ONNX["OnnxModelService (XGBoost/RandomForest ONNX)"]
        ONNX -->|Predicted Bullish Probability| CONF_CALC["Direction-Aware Raw Confidence"]
        CONF_CALC -->|Raw Confidence %| P["CriticAgent"]
        P -->|Apply Penalty Checks - RSI overbought, VIX, Event Risk, OI Walls| Q["Final Adjusted Confidence %"]
        Q -->|Gating check >= 60%| R{"Signal Triggered?"}
        R -->|No| S["Log: No Trade"]
        R -->|Yes| T["Save TradeSignal (ACTIVE)"]
    end

    %% 5. Thesis & Notification Layer
    subgraph AlertLayer ["5. Thesis & Alert Dispatch"]
        T --> U["LlmService (Gemini 2.5 Flash)"]
        U -->|Generate Thesis Explanation| V["TelegramBotService"]
        V -->|Dispatch Formatted Alert| W["Telegram Channel Chat"]
        T --> AE["OrderExecutionService"]
        AE -->|RMS Wallet Sizing & 2% Target ROBO Order| A1
    end

    %% 6. Feedback & Self-Learning
    subgraph FeedbackLayer ["6. Self-Learning Loop"]
        W -->|Trade Outcome - Target, SL, or Expiry| X["TradeResult Table"]
        X -->|Optimize Active Weights| Y["AdaptiveWeightsService"]
        Y -->|Update database weights| CONF_CALC
    end

    %% Styling
    classDef ingestion fill:#e3f2fd,stroke:#0d47a1,stroke-width:2px;
    classDef processing fill:#e8f5e9,stroke:#1b5e20,stroke-width:2px;
    classDef agents fill:#fff3e0,stroke:#e65100,stroke-width:2px;
    classDef scoring fill:#f3e5f5,stroke:#4a148c,stroke-width:2px;
    classDef alert fill:#ffebee,stroke:#b71c1c,stroke-width:2px;
    classDef feedback fill:#e0f7fa,stroke:#006064,stroke-width:2px;

    class A1,A2,B ingestion;
    class C,D,E,F,G,H processing;
    class I,J,K,L,M agents;
    class ONNX,CONF_CALC,P,Q,R,S,T scoring;
    class U,V,W,AE alert;
    class X,Y feedback;
```
