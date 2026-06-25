# Nifty Analysis Engine — Task Tracker

Single source of truth for all improvement work. Check items off as completed.
Legend: `[x]` done · `[ ]` pending · `[~]` in progress

---

## ✅ DONE — Foundation (P0/P1)

### P0 — Trust & safety
- [x] Secrets → env vars (`.env`/`.env.example`/`run-local.sh`); placeholders in `application.yml`
- [x] Decision-path test suite (DecisionAgent, MarketRegimeAgent, RiskGuard, OptionPricing)
- [x] Risk controls: `trading-enabled`, `capital-per-order-percent`, `max-trades-per-day`, `max-loss-per-day`
- [x] Real-LTP trade tracking + INR P&L (quantity column, V1.3 migration, % target/SL)

### P1 — Correctness
- [x] Backtest realism (live-matching gating, brokerage/slippage/theta, INR P&L, honest win-rate)
- [x] Decision provenance (per-signal ONNX vs agent vs blend vs final breakdown)
- [x] Indicator-unify documented as retrain plan (`docs/MODEL_RETRAIN_PLAN.md`)
- [x] Gitignore `.venv` / python caches

---

## 🔴 P0 — Frontend: make the UI render

- [x] **Fix crash: AiSignals.jsx** — added `Button` to import (`Grid` was already imported)
- [x] **Fix crash: NewsIntelligence.jsx** — added `Grid` import
- [x] **LearningCenter.jsx** — verified: `Grid` + `Button` already imported, no bug
- [x] **Centralized API client** — `src/api/client.js` (`VITE_API_BASE_URL`, 8s timeout); all 7 hardcoded URLs migrated across 6 pages
- [x] **Add `VITE_API_BASE_URL`** — added to `.env.production` (empty=same-origin) + new `.env.development`
- [x] **Real connection/error state** — `useBackendStatus` hook pings backend every 15s; header chip now shows Live / Offline — demo data / Connecting…

---

## 🟠 P1 — Honest & complete data sync

- [x] **Analytics/Performance page** — new `/performance` page + nav; live summary cards + backtest runner (date range → net/gross P&L, costs, win-rate, breakdown). Backend key `totalProfitLossPoints` → `totalProfitLossInr` (now genuinely INR).
- [x] **News + Reports honesty** — `live` state added: backend reachable + no rows now shows a real empty-state ("No news/report yet") instead of mock; demo badge only when unreachable. (Populating the tables via a generator is deferred to P2 backend.)
- [x] **Loading / empty / error states** on Dashboard, OptionChain, AiSignals (+ Performance) — `live` state: spinner while loading, empty-state when backend has no rows
- [x] `/signals/active` reviewed — **redundant for current UI** (AiSignals ACTIVE tab already filters `/signals` client-side; a 2nd call would duplicate data). Endpoint kept for external API consumers; intentionally not wired.
- [x] Distinguish real vs mock data visibly — amber "Demo data" badge on Dashboard / OptionChain / AiSignals / MarketReports / NewsIntelligence when backend unreachable; AiSignals & News now trust backend even when empty (no more fake data)

---

## 🟡 P2 — Frontend correctness & polish

- [x] Dashboard: replaced hardcoded change % with **spot vs VWAP** (real intraday anchor) + VIX **volatility-regime** label (no fabricated numbers)
- [x] OptionChain: max-pain verified correct (backend stamps same value on all strikes; now uses `.find` non-null guard); build-up coloring fixed to use **real underlying direction (spot vs VWAP), inverted for puts**
- [x] IV scale — **verified NO mismatch**: AngelOne (`12.5`) and simulated (~10–15) are both percent, frontend shows `%`. Audit's `0.22` was fabricated. No change needed.
- [x] Calculators: fixed real bug — `optRoi` Infinity/NaN when premium=0 guarded. (SIP div-by-zero was already guarded; inputs parse `''`→0, no NaN cascade.)
- [x] Removed stray `frontend/dist.zip`
- [x] `TradingViewChart.jsx` — **verified correct**: `innerHTML = JSON.stringify(config)` is TradingView's official embed pattern. No fix needed.
- [x] **Real-time portal push (WebSocket/STOMP)** — backend `WebSocketConfig` + `MarketStreamPublisher` broadcast `/topic/market`, `/topic/options`, `/topic/signals` on every collection cycle; Dashboard/OptionChain/AiSignals do one initial REST fetch then receive **live pushes** (polling removed); header chip shows "Live • Streaming"; nginx `/ws/` upgrade block added. Frontend client: `@stomp/stompjs` + SockJS (`src/api/marketStream.js`).
- [ ] Set real AdSense ID or remove AdSense (owner action — placeholder ID)

---

## 🟡 P2 — Backend observability & resilience

- [x] **`/health` endpoint** — `GET /api/v1/health` checks DB (critical→200/503), Redis, model-loaded, trading-enabled; frontend connection chip now polls it
- [x] **Date-range filter on `/analytics/summary`** — optional `start`/`end` ISO params (filters via `signal.signalTime`); defaults to all-time
- [ ] Metrics (Micrometer): signals/hr, win-rate, P&L, gate-rejection reasons, API latency, model-ready — *needs actuator/micrometer dependency (not added)*
- [x] **Resilient external calls — timeouts** — `WebClientConfig` already caps connect + response + read/write at 10s globally (now configurable via `nifty.http.*`); a hung Angel One/Gemini/Yahoo call can't block the scheduler. *Retries + circuit-breakers (Resilience4j) intentionally not added — timeouts + per-call fallbacks already prevent hangs.*
- [x] **News/Reports generator** — generation logic (already in `LlmService`) extracted to `ContentGenerationService`; schedulers (7:00/15:35/15:45 IST) now delegate to it; added on-demand `POST /api/v1/reports/generate?type=` and `POST /api/v1/news/generate`; **"Generate now" buttons** on the Reports & News pages. Tables populate on demand instead of only at fixed IST times.
- [x] **Fixed broken simulated mode** — `OptionPricingService` & `OrderExecutionService` made `AngelOneDataClient` optional (`@Nullable` + fallbacks). `provider: simulated` now actually boots (it previously failed DI) — enabling broker-free local testing.

---

## 🔵 Real-time data

- [x] **Portal push (WebSocket/STOMP)** — backend broadcasts market/options/signals every cycle; frontend live, polling removed (see P2 above)
- [x] **Broker live feed (Angel One SmartWebSocketV2)** — opt-in (`nifty.stream.enabled`, default off); `AngelOneStreamClient` streams spot/future/VIX LTP ticks → `MarketTickCache` → `/topic/tick`; Dashboard merges ticks live. Captures `feedToken` at login; binary parser unit-tested (`AngelOneStreamClientTest`). ⚠️ Live socket/auth needs market-hours verification — see `docs/STREAMING.md`.
- [x] **Option-chain streaming (SnapQuote)** — streams per-strike OI+LTP (CE/PE ±10 strikes) in mode 3 alongside the index feed; `parseSnapQuote` (OI@131, unit-tested) → `OptionTickCache` → throttled `/topic/optionsTick`; Option Chain page merges live OI by strike (IV/PCR/MaxPain stay from collect cycle). ⚠️ OI offset needs live confirmation. ATM re-centring intraday is a small follow-up.

---

## ✅ Verification

- [x] **`VERIFICATION.md`** — manual runbook (automated → runtime → UI → restore-safe), mapped to the goal
- [x] **`verify.sh`** — automated smoke test: boots the app in simulated mode (no broker/orders), drives collection cycles, asserts a trade is generated + all endpoints respond + a signal carries the 2% target/quantity + validation returns 400. *(Not runnable in this sandbox — no Docker/DB — but syntax-checked and ready.)*

---

## 🟢 P3 — Engineering quality

- [x] **DB indexes** — V1.4 migration adds indexes on all hot paths: `market_snapshot.snapshot_time`, `option_snapshot(snapshot_time / strike+time)`, `market_candle(timeframe,timestamp)`, `trade_signal(status / signal_time / strike+type+status)`, FK joins (`trade_result.signal_id`, `signal_explanation.signal_id`), `market_news.published_at`, `ai_report(type,publish_date)`. All `IF NOT EXISTS`.
- [x] **OpenAPI/Swagger** — verified already complete (`OpenApiConfig` title/desc/version; springdoc serves Swagger UI; auto-documents new `/health`, `/generate`, summary date-params). API versioned via `/api/v1`.
- [x] **Frontend error boundaries** — `ErrorBoundary` wraps the page pane (resets on route change), so a single page crash shows a recoverable message instead of blanking the whole app.
- [x] **Request validation** — added `spring-boot-starter-validation`; `@Validated` + `@NotBlank`/`@Min`/`@Max` on Report/News params; `GlobalExceptionHandler` returns clean 400s (instead of 500s) for invalid input
- [x] **Frontend type-safety (PropTypes)** — added `prop-types` to all prop-taking components (`ErrorBoundary`, `AdSenseSlot`, `StatCard`). Pages are propless. (Full TS migration intentionally not done — disproportionate for a propless-page app.)

---

## 🔵 Model / ML (from MODEL_RETRAIN_PLAN.md)

- [x] **Canonical feature spec** — `docs/FEATURE_SPEC.md` defines all 8 features (order, formula, range, fallback), the ONNX I/O contract, and known parity risks
- [x] **Java feature-parity test** — `FeatureParityTest` golden-masters the Java extraction to the spec's documented values (fails on drift)
- [x] **Assert ONNX I/O schema at load** — `OnnxModelService.validateSchema()` checks single input `input` + 8 features + outputs present; rejects a mismatched model (falls back to rule-based) instead of feeding wrong-shaped data
- [x] **Retrain pipeline rewritten** — `train_model.py` now does walk-forward validation (TimeSeriesSplit) + majority-class **baseline edge check** (warns if no edge); versioned output `nifty_model_vN.onnx` + auto-generated `MODEL_CARD.md`; `--promote` flag for shadow-then-switch
- [x] **Python hygiene** — `requirements.txt` added; removed runtime pip-install from `train_model.py`
- [ ] **Run the retrain + shadow-compare** — OWNER ACTION: needs network/data to execute `python train_model.py --version 2`, review the model card's edge vs baseline, then `--promote`. (Code is ready; can't run here.)

---