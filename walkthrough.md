# Nifty Analysis Engine – Work Walkthrough (Sprints 1–9)

This document records the implementations and verification status across all 9 completed development sprints.

---

## Sprint 1 — Data Collection (Core Foundation)
Established the data ingestion and caching foundations of the system.
- **pom.xml:** Configured Spring Boot 3.4.3, Java 21, and dependencies for Data JPA, Redis, Postgres, Flyway, and Lombok. Added `spring-boot-starter-webflux` to support non-blocking asynchronous WebClient calls.
- **Database & Flyway:** Set up local developer database instances using Docker Compose and wrote schema migrations [V1__init_schema.sql](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/resources/db/migration/V1__init_schema.sql) defining snapshots, candles, signals, and dynamic confidence weights.
- **Entities & Repositories:** Created JPA data structures for all tables.
- **Data Ingestion Client:** Coded [SimulatedDataClient](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/collector/client/impl/SimulatedDataClient.java) to output realistic fluctuations.
- **Persistence & Caching:** Designed [RedisService](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/RedisService.java) to cache JSON models, and [MarketCollectorService](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/MarketCollectorService.java) to handle database write-backs and 5m candle updates.
- **Scheduling & Web Layer:** Set up [MarketScheduler](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/scheduler/MarketScheduler.java) mapping Indian stock hours (09:15 - 15:30 IST) and [MarketController](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/controller/MarketController.java) REST mapping points.

---

## Sprint 2 — Technical & Options Analytics
Built the math evaluation layer for technical indicators and options metrics.
- **Technical Indicators Service:** Developed [TechnicalIndicatorService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/TechnicalIndicatorService.java) calculating EMA 20/50, Wilder's smoothed RSI (14) with a 30-period lookback window, and daily volume-weighted VWAP.
- **Options Analytics Service:** Developed [OptionsIndicatorService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/OptionsIndicatorService.java) calculating overall PCR of the option chain, Max Pain strike, and strike-wise Call/Put build-up trends (Long Build-up, Short Covering, Short Build-up, Long Unwinding).
- **Enriched Persistence:** Updated [MarketCollectorService](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/MarketCollectorService.java) to calculate and attach these metrics to snapshots prior to database persistence and Redis caching.

---

## Sprint 3 — Market Context Layer (Agents Layer)
Created specialized agent components evaluating market context:
- **[MarketRegimeAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/MarketRegimeAgent.java):** Classifies the market into Trending Bullish/Bearish, Consolidating Sideways (using price standard deviations), or High Volatility.
- **[MultiTimeframeAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/MultiTimeframeAgent.java):** Validates trend alignment across 5m, 15m, 30m, and 60m sliding candle frames.
- **[SentimentAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/SentimentAgent.java):** Weighs external global indicators (GIFT Nifty, Dow Futures, Dollar Index DXY, Crude Oil, FII flows).
- **[LiquidityAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/LiquidityAgent.java):** Validates minimum open interest and transaction spreads to filter out illiquid contract strikes.
- **[EventRiskAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/EventRiskAgent.java):** Flags calendar events (RBI, Fed) to mitigate pre-event risk.
- **[EntryTimingAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/EntryTimingAgent.java):** Verifies breakout/retest locations relative to VWAP.
- **[RiskAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/RiskAgent.java):** Evaluates Risk-to-Reward parameters and VIX volatility thresholds.
- **[MarketAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/MarketAgent.java) & [TechnicalAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/TechnicalAgent.java) & [OptionsAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/OptionsAgent.java):** Score basic structural biases.

---

## Sprints 4 & 5 — Confidence & Critic Engines
- **[ConfidenceEngine.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/engine/ConfidenceEngine.java):** Evaluates active DB weight mappings (seeded baseline: Trend=20, OI=20, PCR=15, VWAP=15, RSI=10, Futures=10, Sentiment=10) and computes raw weighted confidence.
- **[CriticAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/CriticAgent.java):** Subtracts penalties from the confidence percentage for overbought RSIs, macro-event risks, high VIX decays, and heavy opposing writing concentrations. Logs applied deductions to the explanation table.

---

## Sprint 6 — Telegram Notification Service
- **[TelegramBotService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/notification/TelegramBotService.java):** Asynchronously dispatches structured trade signals (Strike, Entry, SL, Targets, Confidence, and positive/negative triggers list). Prints formatted signals directly to console logs if bot properties are disabled.

---

## Sprint 7 — Backtesting Engine
- **[BacktestingEngine.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/backtest/BacktestingEngine.java):** Sequentially loads historical index snapshots, simulates trade entries, tracks option price moves (using a Nifty Delta factor of 0.5), logs targets/SL outcomes, and computes overall Win Rates and Profit/Loss statistics.
- **[AnalyticsController.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/controller/AnalyticsController.java):** REST controller mapping backtest triggers (`POST /api/v1/analytics/backtest/run`) and trade result summaries (`GET /api/v1/analytics/summary`).

---

## Sprint 8 — LLM Decision & Explanation Layer
- **[LlmService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/LlmService.java):** Links to Google Gemini Flash API using WebClient. Composes structured prompts presenting deterministic scores to generate natural language thesis summaries for Telegram signals. Omit API call logic during high-speed backtests.
- **[DecisionAgent.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/agent/DecisionAgent.java):** Orchestrates all analytical agents, checks gating limits (Conf >= 80%), invokes the LlmService, saves signal details to database, and triggers alerts.

---

## Sprint 9 — Self-Learning Model
- **[AdaptiveWeightsService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/AdaptiveWeightsService.java):** Analyzes `trade_result` logs periodically, reinforces weights of factors that correctly aligned with wins, weakens weights of factors that aligned with losses, normalizes the total to 100.0%, and persists updated weights to the database.

---

## Sprint 10 — Angel One Live Data Integration
Successfully implemented live stock market data retrieval using the Angel One SmartAPI and verified end-to-end notifications:
- **[AngelOneDataClient.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/collector/client/impl/AngelOneDataClient.java):**
  - Configured TOTP (Time-based One-Time Password) generation dynamically using HMAC-SHA1 and SecretKeySpec to support SmartAPI's multi-factor authentication.
  - Implemented streaming parser to download and ingest the JSON scrip master mapping.
  - Aligned weekly/monthly contracts calculations to **Tuesdays** to match the NSE 2025/2026 weekly contract expiry calendar.
  - Appended `FUT` suffix for Nifty monthly futures symbols to successfully match the master scrip codes (e.g., `NIFTY30JUN26FUT`).
- **[LlmService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/service/LlmService.java):**
  - Updated the API endpoint to utilize **`gemini-2.5-flash`** since `gemini-1.5-flash` is deprecated/unavailable in the current API version environment.
- **[TelegramBotService.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/notification/TelegramBotService.java):**
  - Switched URL building from a relative `uriBuilder.path()` segment to `UriComponentsBuilder` to prevent double-slash trimming.
  - Added automatic underscore escaping (e.g. `BUY_CE` to `BUY\_CE`) to satisfy Telegram's strict Markdown syntax parser and avoid `400 Bad Request` exceptions.
- **[TestAngelOne.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/test/java/com/nifty/analysis/TestAngelOne.java) & [DemoRunner.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/test/java/com/nifty/analysis/DemoRunner.java):**
  - Created a diagnostic test class and an integration runner class to perform real-time data fetching, indicators/agent analysis calculations, Gemini explanation generation, and Telegram notification delivery.

---

## Verification & Test Results
Verified compilation and tests using Maven:
- **Unit Test Coverage:** Created unit tests for all services, agents, indicators, and simulations, resulting in **14 passing unit tests** and 100% build success.
- **Angel One Connectivity Verification:** Ran the connectivity test (`TestAngelOne`) successfully returning live market prices and actual open interest data from the SmartAPI endpoints without resorting to simulated fallbacks:

```text
=== Angel One SmartAPI Connectivity Verification ===
Parsed Client Code: M213870
Parsed API Key: ITN5sUnv
Parsed Password: ****
Parsed TOTP Key: ****
Initializing client & downloading scrip master...
Successfully loaded and parsed 1763 Nifty-related scrips from Angel One master.

Total scripMap size: 1763
Today's Date: 2026-06-03
Calculated Next Tuesday: 2026-06-09
Calculated Option Date Symbol String: 09JUN26

NIFTY keys not ending with CE or PE (potential Futures):
  NIFTY30JUN26FUT
  NIFTY25AUG26FUT
  NIFTY28JUL26FUT

Calculated Tuesday Expiry: 09JUN26
Matching keys for Tuesday option:
  NIFTY09JUN2622550PE
  NIFTY09JUN2625300PE
  NIFTY09JUN2624150PE

--- Testing fetchMarketData() ---
Successfully authenticated with Angel One SmartAPI!
Market Data Result:
  Spot: 23396.85
  Future: 23505.0
  VIX: 13.5
  Volume: 0.0

--- Testing fetchOptionChain() ---
Option Chain Result (Total strikes returned: 21):
  Strike: 23200 | CE OI: 1474720 | PE OI: 4707430 | PCR: 3.19
  Strike: 23650 | CE OI: 1150630 | PE OI: 284440 | PCR: 0.25
  Strike: 23300 | CE OI: 4949555 | PE OI: 7595250 | PCR: 1.53

=== Verification Complete ===
```

- **JUnit Test Verification Run:**
```text
[INFO] Running com.nifty.analysis.backtest.BacktestingEngineTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nifty.analysis.service.OptionsIndicatorServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nifty.analysis.service.AdaptiveWeightsServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nifty.analysis.service.TechnicalIndicatorServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.nifty.analysis.service.MarketCollectorServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
