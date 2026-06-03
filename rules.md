# Nifty Analysis Engine – Coding Standards & Trading Domain Guidelines

This document outlines the coding standards, repository practices, database guidelines, and domain-specific stock market rules for developers and agent systems contributing to the Nifty Analysis Engine.

---

## 1. Technical & Coding Standards

### Java 21 Features
- **Data Models:** Use Java `record` classes for all immutable Data Transfer Objects (DTOs), API responses, and message payloads.
- **Pattern Matching & Switch Expressions:** Use modern switch expressions and pattern matching for type checks and regime logic.
- **Sequenced Collections:** Use `SequencedCollection` (like `LinkedHashSet` or `ArrayList`) for keeping ordered data.

### Spring Boot 3.5 Guidelines
- **Dependency Injection:** Enforce constructor injection. Use Lombok's `@RequiredArgsConstructor` on classes needing dependencies. Do not use `@Autowired` on fields.
- **HTTP client calls:** Use `WebClient` for external API integration (Fyers, Kite, Telegram, LLM APIs).
- **Asynchronous & Scheduling:** Enable scheduling using `@EnableScheduling`. Keep scheduler pools configured properly to prevent blockages.
- **Caching:** Cache the latest snapshot states in Redis. Keys must follow standard prefixes:
  - `market:latest` -> Stores JSON of latest MarketSnapshot
  - `option:latest` -> Stores JSON of latest option chain snapshots
  - Set appropriate Time-To-Live (TTL) on cached values (e.g., 2 minutes for live ticks, 24 hours for daily FII/DII data).

### Database & JPA Guidelines
- **Schema Management:** All database schema changes must go through Flyway migrations placed in `src/main/resources/db/migration/`. Never modify database structures manually.
- **Entity Definitions:** JPA entities must use explicit `@Table` naming and Snake Case for column names (e.g., `snapshot_time`).
- **N+1 Query Prevention:** Use `@EntityGraph` or JOIN FETCH in repositories when loading entities with nested relationships (e.g. loading a signal with its explanations).
- **Performance Indexing:** Index columns frequently queried in range searches or WHERE clauses, particularly:
  - `snapshot_time` on `market_snapshot` and `option_snapshot`
  - `timestamp` and `timeframe` on `market_candle`
  - `signal_id` on `trade_result` and `signal_explanation`

---

## 2. Trading & Option Chain Domain Rules

### Indian Stock Market Hours
- **Market Hours:** 09:15 AM to 03:30 PM Indian Standard Time (IST), Monday through Friday.
- **Pre-Market and Post-Market:** Block signal generation before 09:15 AM and after 03:30 PM.
- **Intraday Reset:** Reset cumulative intraday indicators (like VWAP and daily RSI) at 09:15 AM each trading day.

### Option Chain Terminology & Calculations
- **Strike Selection (ATM, ITM, OTM):**
  - *At-The-Money (ATM):* The strike price closest to the current Nifty Spot price.
  - *In-The-Money (ITM):* Strikes below Spot for CE (Call), and strikes above Spot for PE (Put).
  - *Out-of-The-Money (OTM):* Strikes above Spot for CE, and strikes below Spot for PE.
- **Put-Call Ratio (PCR):**
  $$\text{PCR} = \frac{\sum \text{Put Open Interest (OI)}}{\sum \text{Call Open Interest (OI)}}$$
  - A PCR > 1.0 indicates bullish sentiment (higher Put writing than Call writing).
  - A PCR < 0.7 indicates bearish sentiment (higher Call writing than Put writing).
- **Max Pain:** The strike price at which option buyers would lose the maximum amount of money (and option sellers would make the most profit) upon expiry. Calculated by finding the strike that minimizes total seller exposure.
- **Implied Volatility (IV):** A measure of market expectations of future volatility. High IV increases option premiums, making option buying riskier due to potential "IV crush" (sharp drops in IV, lowering option values).

### Open Interest (OI) Build-Up Logic
Determine the build-up classification per strike using the relationship between Price change and Open Interest change:

| Price Change | OI Change | Build-Up Type | Market Sentiment |
| :--- | :--- | :--- | :--- |
| **Rising (+)** | **Rising (+)** | **Long Build-up** | Highly Bullish (Buyers entering fresh longs) |
| **Rising (+)** | **Falling (-)** | **Short Covering** | Bullish (Sellers rushing to cover short positions) |
| **Falling (-)** | **Rising (+)** | **Short Build-up** | Highly Bearish (Sellers entering fresh shorts) |
| **Falling (-)** | **Falling (-)** | **Long Unwinding** | Bearish (Buyers exiting long positions) |

---

## 3. Multi-Agent & Decision Engine Constraints

- **Mathematical Determinism First:** All signals must be generated and priced using mathematical, technical, and option chain logic. Under no circumstances should an LLM invent price entries, stop-loss triggers, target figures, or confidence scores.
- **LLM Role (Sprint 8):** The LLM's role is strictly to explain the trade setup. It consumes the deterministic metrics, checks the agent reports, translates the technical evidence into structured natural language reasons, and constructs the final Telegram message format.
- **Decoupled Architecture:** Each agent (Market, Technical, Options, Liquidity, Sentiment) must operate as an independent component receiving a standard context object and returning a typed DTO containing its score and reasoning log.
- **Strict Invalidation (Critic Agent):** The Critic Agent possesses absolute veto power or heavy penalty weights. If the Critic flags a critical risk (e.g., massive Call writing barrier or upcoming high-impact economic news), it must deduct sufficient weight to pull the final confidence score below the 80% threshold, preventing the trade.
