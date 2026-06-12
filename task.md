# Nifty Analysis Engine – Project Task Checklist (Completed)

Track development status across all sprints of the Nifty Analysis Engine.

---

## [x] Sprint 1 — Data Collection (Core Foundation)
- [x] Project Initialization & Infrastructure Config
  - [x] Create standard `.gitignore`
  - [x] Create root `pom.xml` with Java 21 & Spring Boot dependencies
  - [x] Create `docker-compose.yml` for PostgreSQL 16 & Redis 7
- [x] Database Schema Setup
  - [x] Create Flyway migration `V1__init_schema.sql` with V2 tables
- [x] Entities & Repositories
  - [x] Create JPA entities (`MarketSnapshot`, `MarketCandle`, `OptionSnapshot`, `TradeSignal`, `SignalExplanation`, `TradeResult`, `ConfidenceWeight`)
  - [x] Create JPA repository interfaces for all entities
- [x] Ingestion Client & Services
  - [x] Create `MarketDataClient` and `OptionChainClient` interfaces
  - [x] Implement `SimulatedDataClient` for realistic mock data generation
  - [x] Create `RedisService` for fast snapshot caching
  - [x] Create `MarketCollectorService` to persist data in DB and cache in Redis
- [x] Scheduling & Web Layer
  - [x] Create `application.yml` with DB, Redis, and custom config
  - [x] Create `NiftyAnalysisApplication` entry point
  - [x] Create `MarketScheduler` to trigger collection periodically
  - [x] Create `MarketController` for manual status check and data polling
- [x] Verification
  - [x] Compile and package the application (`mvn clean install` passes)
  - [x] Write and run JUnit 5 tests confirming entities mapping, database saves, and Redis caching

---

## [x] Sprint 2 — Technical & Options Analytics
- [x] Technical Indicator Computations
  - [x] Implement service/utility to calculate EMA 20 and EMA 50
  - [x] Implement service/utility to calculate RSI (14)
  - [x] Implement service/utility to calculate Volume Weighted Average Price (VWAP)
- [x] Options Analytics Computations
  - [x] Implement Put-Call Ratio (PCR) trend analysis
  - [x] Implement Max Pain strike calculator
  - [x] Implement Open Interest (OI) Build-up Detection per strike (Long Build-up, Short Covering, Short Build-up, Long Unwinding)
- [x] Indicator Persistence Enrichment
  - [x] Update `MarketCollectorService` to compute and save indicators to `MarketSnapshot` during collection
- [x] Verification
  - [x] Add unit tests verifying indicators calculations

---

## [x] Sprint 3 — Market Context Layer
- [x] Implement **Market Regime Agent** (Trending Bullish/Bearish, Sideways, High Volatility, Event Driven)
- [x] Implement **Multi-Timeframe Agent** (Checks price alignment across 5m, 15m, 30m, 60m candles)
- [x] Implement **Sentiment Agent** (Parses global indicators, US markets index trends, and daily FII/DII figures)
- [x] Implement **Liquidity Agent** (Filters out illiquid strikes using bid-ask spreads, volume, and minimum OI)
- [x] Implement **Event Risk Agent** (Monitors economic schedules and drops flags before high-volatility news)
- [x] Verification
  - [x] Test regime classification and multi-timeframe checks with mock candles

---

## [x] Sprint 4 — Confidence Engine
- [x] Configuration DB Loader
  - [x] Write logic to fetch dynamic weights from `confidence_weight` table
- [x] Weighted Evaluation Engine
  - [x] Implement scoring logic aggregating agent evaluations based on active database weights (0-100 score)
- [x] Gating Rule Enforcer
  - [x] Implement entry gating (restrict signal generation unless score >= 80)
- [x] Verification
  - [x] Write tests verifying exact mathematical score aggregations

---

## [x] Sprint 5 — Critic Engine
- [x] Invalidation Rules Engine
  - [x] Write rules assessing divergent price actions, heavy Call writing barriers, weak volume breakouts, and VIX expansions
- [x] Penalty Injector
  - [x] Write logic subtracting percentage penalties from the raw confidence score
- [x] Persistence Layer
  - [x] Persist factor explanations to the `signal_explanation` table
- [x] Verification
  - [x] Test critic deductions to verify that failing conditions properly pull signals below the 80% limit

---

## [x] Sprint 6 — Telegram Notification Service
- [x] Bot Configuration
  - [x] Set up bot tokens and channel destination IDs in `application.yml`
- [x] Alert Dispatcher
  - [x] Build Spring WebClient integration calling Telegram SendMessage endpoint
- [x] Signal Formatter
  - [x] Implement template formatting to print Strike, entry ranges, target goals, stop-loss limits, score, and reasons

---

## [x] Sprint 7 — Backtesting Engine
- [x] Replay System
  - [x] Write scheduler/service to load historical database candles and snapshots sequentially
- [x] Position Simulator
  - [x] Write order simulation logic verifying entries, stop-loss triggerings, and target reachings
- [x] Statistics Exposer
  - [x] Create REST endpoints outputting overall P&L, Win Rate, profit factors, and average holding times
- [x] Verification
  - [x] Run backtesting simulation on simulated data and check results persistence

---

## [x] Sprint 8 — LLM Decision & Explanation Layer
- [x] Spring AI / LangChain4j Integration
  - [x] Configure LLM dependencies and API keys
- [x] Decision Summarizer Agent
  - [x] Implement prompting structure presenting mathematical metrics and agent inputs to LLM
- [x] Explanations Formatter
  - [x] Use LLM completion output to format the final Telegram text explaining the trade thesis

---

## [x] Sprint 9 — Self-Learning Model
- [x] Performance Auditor
  - [x] Build scheduler analyzing recent successful vs failed signals from `trade_result`
- [x] Optimization Engine
  - [x] Implement adjustment formulas mapping correlations and tuning active `confidence_weight` values
- [x] Persistence Updater
  - [x] Update table values dynamically, logging historical shifts in engine weights

---

## [x] Sprint 10 — Angel One Live Data Integration
- [x] Live Ingestion Client
  - [x] Implement TOTP generation and Angel One login flow
  - [x] Add master scrip downloading and mapping
  - [x] Connect Nifty Index and Futures fetching
  - [x] Add live options chain mapping
- [x] LLM Model Upgrade
  - [x] Upgrade model to `gemini-2.5-flash` in `LlmService`
- [x] Verification
  - [x] Build and run test connections class

---

## [x] Sprint 11 — 30-Minute Status Updates & Live Active Trade Tracking
- [x] Repository Layer
  - [x] Add query to find signals by time in `TradeSignalRepository`
- [x] Notification & AI Services Layer
  - [x] Add generic `sendMessage` method to `TelegramBotService`
  - [x] Add `generateMarketSummary` to `LlmService` with template fallback
- [x] Core Business Logic & Ingestion Layer
  - [x] Implement `updateActiveTrades` in `MarketCollectorService` and call in `collect()`
  - [x] Implement `send30MinSummary` in `MarketCollectorService`
- [x] Scheduler Layer
  - [x] Add `@Scheduled` task `send30MinUpdate` to `MarketScheduler`
- [x] Verification
  - [x] Run existing unit tests to confirm build status
  - [x] Verify functionality via a custom mock test or integration test

---

## [x] Sprint 12 — Swagger API Documentation
- [x] Dependencies Setup
  - [x] Add `springdoc-openapi-starter-webmvc-ui` dependency to `pom.xml`
- [x] Configuration Setup
  - [x] Create [OpenApiConfig.java](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/src/main/java/com/nifty/analysis/config/OpenApiConfig.java) with customized OpenAPI definition
- [x] Verification
  - [x] Verify compilation and test runs remain green

---

## [/] Sprint 13 — Nifty Intelligence Portal Extensions

### [x] Phase 1: Foundation & Backend Setup
- [x] Database Schema Setup
  - [x] Create Flyway migration `V1.2__portal_extensions.sql` defining `ai_report`, `market_news`, and `learning_article` tables
  - [x] Add default articles data in the migration file for learning center
- [x] Backend Domain Layer (Entities & Repositories)
  - [x] Create `AiReport` entity and repository
  - [x] Create `MarketNews` entity and repository
  - [x] Create `LearningArticle` entity and repository
- [x] Web Layer Configurations
  - [x] Create `WebConfig.java` to support CORS requests from port `3000` (React local host)
- [x] Verification
  - [x] Run `mvn clean compile` to check that new models and configurations build successfully

### [x] Phase 2: Schedulers & Controllers (AI Vlogs & Market Data APIs)
- [x] AI Report Scheduling (Daily Vlogs)
  - [x] Create `ReportScheduler.java` triggering at:
    - **07:00 AM IST** (Pre-Market Morning View vlog)
    - **03:35 PM IST** (Post-Market Daily Close vlog)
  - [x] Inject `LlmService` to generate structured market vlogs via Gemini
- [x] API Controllers Development
  - [x] Implement `ReportController` to serve latest reports and archives
  - [x] Implement `NewsController` to serve AI summarized top daily events
  - [x] Implement `LearningArticleController` to serve education cards
  - [x] Implement `SignalController` returning raw trading opportunities to all users
- [x] Verification
  - [x] Add unit tests verifying report fetching and signal serialization REST endpoints (all tests passing)

### [x] Phase 3: Frontend Initialization & UI Theme
- [x] Project Initialization
  - [x] Initialize React 19 / Vite workspace in a new `/frontend` folder
  - [x] Setup dependencies in `package.json` (`@mui/material`, `@mui/icons-material`, `recharts`, `axios`, `react-router-dom`)
  - [x] Set Vite configuration server port to `3000`
- [x] UI Theme Layout
  - [x] Create `theme.js` defining premium dark-mode aesthetic palette
  - [x] Build global layout sidebar navigation panel matching mockup design
- [x] AdSense Infrastructure
  - [x] Inject AdSense asynchronously into `frontend/index.html` using env client keys
  - [x] Code reusable `<AdSenseSlot />` wrapper component

### [x] Phase 4: Frontend Pages Development
- [x] Dashboard View
  - [x] Build key-metric layouts (Nifty Spot/Future, VIX, Gift Nifty)
  - [x] Implement Put-Call Ratio (PCR) sentiment visual dial
  - [x] Embed TradingView Candlestick Chart Widget
  - [x] Design Daily Support/Resistance values cards
- [x] Option Chain Layout
  - [x] Design Strike Price table (Calls on left, Puts on right) with ATM strike highlights
  - [x] Highlight rows dynamically based on Option build-up types (Long Build-up, Short Covering, Short Build-up, Long Unwinding)
  - [x] Integrate Recharts charts for Max Pain strike and historical PCR trends
- [x] Signals & Daily Vlogs View
  - [x] Renders Signal Cards including strike, targets, stop-losses, confidence dial, and thesis explanations
  - [x] Build AI Reports tab displaying Pre-Market and Post-Market vlogs
- [x] Calculators, News & Learning Center
  - [x] Implement interactive calculators (Option Profit, Position Size, Risk Reward, SIP, Brokerage) with Grid import fixes
  - [x] Display daily "Top 5 Events Impacting Nifty" in News dashboard
  - [x] Render grid list of learning articles and read guides
- [x] Ad Placement Binding
  - [x] Mount `<AdSenseSlot />` blocks in Sidebar, Option Chain footer, Article bodies, and Calculators tabs

### [/] Phase 5: Verification & Hosting Deployment
- [/] Comprehensive Testing
  - [x] Run backend tests suite: `mvn test` (all 32/32 tests green)
  - [ ] Run play-verification tests confirming frontend renders pages successfully (local Vite dev server tested; browser subagent blocked by CDP compatibility limits)
- [x] Production Build & Configuration
  - [x] Build frontend production bundle (`npm run build` succeeds cleanly)
  - [x] Set up Nginx configuration file directing traffic to static files and reverse proxying api calls
  - [x] Configure `docker-compose` settings for PostgreSQL and Redis host-volumes backup


