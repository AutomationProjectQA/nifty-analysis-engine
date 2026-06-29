# Nifty Analysis Engine — Issues & Backlog

> Living document of known issues, fixes, and future work.
> Created 2026-06-29. Branch in flight: `fix/production-data-chain` (changes below are **implemented but not yet committed/merged** unless stated otherwise).
> Status legend: ✅ Fixed (this branch) · 🔄 Partial / needs verification · ⛔ Open / not started

---

## 1. How to read this document

- **Section 2** — production issues reported by the owner and their fix status.
- **Section 3** — the shared root causes behind most of them.
- **Section 4** — open follow-ups and future work (the real backlog).
- **Section 5** — placeholder for the external (ChatGPT) code-review findings — to be filled in once the content is available (the share link could not be accessed by the tooling).
- **Section 6** — verification & deployment checklist.

---

## 2. Reported production issues (owner)

### 2.1 NIFTY spot & futures show wrong values / not live / only change on refresh — ✅ Fixed
- **Symptoms:** spot & futures wrong, no live updates without a page refresh.
- **Root causes:**
  - Real-time tick stream was disabled (`nifty.stream.enabled: false`).
  - Live broker quote silently fell back to fabricated "simulated" data while the UI labelled it "Live".
- **Fixes:**
  - Enabled the tick stream (`application.yml`).
  - Tick-sanity guard: streamed ticks validated against the trusted per-cycle REST snapshot; implausible ticks dropped + logged (`AngelOneStreamClient.isPlausible`, `MarketTickCache` reference).
  - Honest feed labelling: Dashboard & Option Chain now show Live / Simulated / Offline via `useFeedStatus`.
- **Files:** `service/MarketTickCache.java`, `collector/client/impl/AngelOneStreamClient.java`, `service/MarketCollectorService.java`, `controller/MarketController.java` (`/market/feed-status`), `frontend/src/pages/Dashboard.jsx`, `OptionChain.jsx`, `hooks/useFeedStatus.js`, `resources/application.yml`.
- **Related:** see 2.9 (the index-quote root cause that made values wrong even when "Live").

### 2.2 Option Chain — wrong data, not live, no LTP — ✅ Fixed
- **Root causes:** broker LTP was read then discarded (never added to DTO/entity); the LTP-bearing `/topic/optionsTick` stream was ignored by the UI; the table had no LTP column.
- **Fixes:** added `ceLtp`/`peLtp` to `OptionSnapshotDto` + `OptionSnapshot` entity + migration `V1.9`; populated in `AngelOneDataClient` & `MarketCollectorService`; UI now has CE/PE LTP columns and merges LTP from `/topic/optionsTick`.
- **Files:** `dto/OptionSnapshotDto.java`, `entity/OptionSnapshot.java`, `db/migration/V1.9__add_option_ltp.sql`, `collector/client/impl/AngelOneDataClient.java`, `service/MarketCollectorService.java`, `frontend/src/pages/OptionChain.jsx`.

### 2.3 Strategy Builder — many strikes show ₹0 — ✅ Fixed
- **Root cause:** premiums were always Black-Scholes; when spot ≤ 0 (no snapshot / empty chain) the price returned 0; far-OTM strikes also priced ~0.
- **Fix:** `OptionPremiumService` now prefers real broker LTP, falling back to Black-Scholes only when LTP is absent.
- **Files:** `service/OptionPremiumService.java`, `frontend/src/pages/StrategyBuilder.jsx` (copy update).

### 2.4 AI Signals — zero trades despite a clear 70–90 pt move — ✅ Fixed (softened, not removed)
- **Root causes:** an open move at 09:15–09:30 hit a **hard blackout window**; a sharp move also tripped a **VWAP over-extension hard-skip**; plus an instrument-key bug (`dataFeedStatus.update("NIFTY")` instead of the actual instrument).
- **Fixes (per owner: soften, don't remove):** the volatile-window and VWAP-over-extension hard-skips are now **confidence penalties** (`nifty.timing.volatile-window-gate-penalty:10`, `nifty.signal.entry-overextension-penalty:15`); fixed the instrument-key bug.
- **Files:** `agent/DecisionAgent.java`, `collector/client/impl/AngelOneDataClient.java`.
- **Note:** owner intentionally runs 2% target / ~20–40% stop — risk gates were preserved, only the two intraday hard-skips were softened.

### 2.5 Market News Intelligence — same boilerplate every time, not real news — ✅ Fixed
- **Root cause:** the Gemini call silently fell back to a hardcoded template (FII 450 Cr, GIFT = Dow×0.15, etc.) — `gemini-2.5-flash` "thinking" tokens consumed the whole output budget (no `generationConfig`), returning empty content → template; FII was a hardcoded constant; GIFT was derived, not real.
- **Fixes:** `callGemini` now sets `generationConfig` (`maxOutputTokens`, `thinkingBudget:0`) and a hardened parser that logs `finishReason`/`promptFeedback`. Added `NewsRssClient` that pulls **real headlines** from financial RSS feeds (no API key, XXE-hardened); `generateDailyNews` persists each real headline (deduped, with source link) and an AI "Top 5 Events" summary grounded in those headlines. Removed the fabricated FII number. UI shows a "Read full story" link.
- **Files:** `service/LlmService.java`, `service/ContentGenerationService.java`, `collector/client/impl/NewsRssClient.java`, `repository/MarketNewsRepository.java`, `frontend/src/pages/NewsIntelligence.jsx`.

### 2.6 Daily AI Market Reports — pre-market shows only a one-liner — ✅ Fixed
- **Root cause:** same Gemini truncation issue; the one-liner was the fallback string.
- **Fix:** same `LlmService.callGemini` fix (output budget + thinking off + parser hardening).
- **Files:** `service/LlmService.java`.

### 2.7 Learning Center — modal won't scroll; content too shallow — ✅ Fixed
- **Root causes:** `DialogContent` had `mt:2` with no scroll/max-height; content was DB-seeded with only 3 short articles.
- **Fixes:** modal uses `scroll="paper"` + `DialogContent dividers` + Paper `maxHeight:90vh`; expanded all 3 articles to zero-to-expert depth via migration `V1.10`; mirrored into the offline mock for parity (no LaTeX/tables — no `remark-gfm`).
- **Files:** `frontend/src/pages/LearningCenter.jsx`, `db/migration/V1.10__expand_learning_articles.sql`.

### 2.8 Dashboard chart showed Apple (AAPL) instead of Nifty — ✅ Fixed
- **Root cause (verified in a real browser):** the free TradingView embed widget rejects **all NSE symbols** ("This symbol is only available on TradingView") — NSE:NIFTY, NSE:NIFTY1!, NSE:NIFTYBEES all fail and it falls back to NASDAQ:AAPL. NSE index data is licensed; the free widget can never show Nifty.
- **Fix:** replaced with a self-rendered candlestick chart (`lightweight-charts`) fed by our own candle data via new endpoint `GET /api/v1/market/candles`; live updates via `/topic/market` (new bars) + `/topic/tick` (forming bar). IST time axis. Deleted the dead `TradingViewChart.jsx`.
- **Files:** `frontend/src/components/NiftyCandleChart.jsx`, `controller/MarketController.java`, `frontend/src/pages/Dashboard.jsx`, `package.json` (added `lightweight-charts`).

### 2.9 Dashboard stats wrong; "Live" chip shown but spot ≈ 23,500 (real ~24,000) — ✅ Fixed
- **Root cause:** Angel One FULL quote returned `status:true` but the **NIFTY index token was not in `fetched`** (indices are frequently rejected by FULL mode → land in `unfetched`); old code kept the hardcoded default (~23,500) **and still marked the feed "Live"** → every value (spot, prev close, high/low, 52-week) was wrong while the chip lied.
- **Fixes:** only mark "Live"/return real data when the spot is actually parsed (`ltp>0`); added an **OHLC-mode fallback** (`fetchIndexViaOhlc`) that reliably returns index LTP + day open/high/low/close when FULL rejects the index; honest simulated fallback + ERROR log naming requested/fetched/unfetched tokens if both fail.
- **Files:** `collector/client/impl/AngelOneDataClient.java`.
- **Caveat:** 52-week high/low is only returned in FULL mode. If the account's index only works in OHLC mode, 52-week shows "—" until a historical source is added (see 4.4).

### 2.10 New feature — NIFTY key stats on Dashboard (today high/low, 52-week high/low, prev close) — ✅ Done
- Added 5 fields end-to-end (`MarketSnapshotDto`, `MarketSnapshot` entity, migration `V1.11`), populated from the Angel One quote, surfaced as a "NIFTY 50 Key Statistics" card with a day-change badge.
- **Files:** `dto/MarketSnapshotDto.java`, `entity/MarketSnapshot.java`, `db/migration/V1.11__add_snapshot_ohlc_52week.sql`, `collector/client/impl/AngelOneDataClient.java`, `collector/client/impl/SimulatedDataClient.java`, `service/MarketCollectorService.java`, `frontend/src/pages/Dashboard.jsx`.

---

## 3. Shared root causes (themes)

1. **Silent degradation to fake data.** Multiple paths returned hardcoded/simulated values while presenting them as live (market quote, option chain, LLM content). Fix pattern applied: only claim "live/real" when real data was genuinely obtained; otherwise fall back honestly and log loudly.
2. **External free services don't serve NSE/Nifty.** TradingView free widget (chart) and Gemini-without-config (reports) both failed silently. Moved the chart in-house; hardened the LLM call.
3. **Angel One index quirks.** Index tokens behave differently from equities/derivatives (FULL-mode rejection, OHLC fallback, 52-week availability). Centralised handling in `AngelOneDataClient`.

---

## 4. Open follow-ups / future work (BACKLOG)

### 4.1 Verify the live tick-stream binary offsets — 🔄 High
- `AngelOneStreamClient.parseTick`/`parseSnapQuote` use documented SmartWebSocketV2 byte offsets (LTP@43, Volume@67, OI@131). These are unit-tested offline but **must be confirmed during live market hours**. The new plausibility guard will log `"dropping implausible … tick … wrong binary frame offset"` if offsets are off — watch for it on the first live session.

### 4.2 Real FII / DII data source — ⛔ Medium
- FII is currently **omitted** (was a fabricated 450 Cr constant). NSE blocks bots, so a reliable source is needed (NSE provisional cash data, a paid data vendor, or the broker). Until then the news summary won't cite FII/DII flows.

### 4.3 Real GIFT Nifty quote — ⛔ Low/Medium
- GIFT Nifty is still derived as `Dow futures × 0.15` in `YahooFinanceClient` (now labelled an estimate). Replace with a real GIFT Nifty / NSE-IX quote if a source is available.

### 4.4 52-week high/low for the index when FULL mode is unavailable — ⛔ Medium
- If the index only resolves in OHLC mode, 52-week shows "—". Add a source: compute from ~1 year of daily candles via Angel One's historical candle API (`getCandleData`) and cache it (refresh daily).

### 4.5 Expand "real news" breadth & ranking — ⛔ Low
- Currently 2–3 RSS feeds (Moneycontrol, Business Standard). Consider adding more feeds, relevance filtering to Nifty/Bank Nifty, and dedupe across sessions over a longer window.

### 4.6 Frontend bundle size — ⛔ Low
- Vite warns the main chunk > 500 kB. Consider route-level code-splitting (`React.lazy`) for the heavier pages (charts, recharts, lightweight-charts).

### 4.7 Pre-existing console warnings — ⛔ Low
- MUI `primaryTypographyProps` warnings appear in the console (a `ListItemText` usage). Cosmetic; clean up when touching that component.

### 4.8 BANKNIFTY live path — ⛔ Medium
- Live fetch is wired for NIFTY only; other instruments return simulated data (and the risk guard refuses to trade them). Wire real BANKNIFTY tokens/quote before enabling it for trading.

### 4.9 Commit & deploy the in-flight branch — 🔄 High
- All of Section 2 is on `fix/production-data-chain`, **uncommitted**. Needs commit, review, and redeploy (backend + frontend + run new Flyway migrations V1.9–V1.11). See Section 6.

---

## 5. External code-review findings (ChatGPT) — ✅ CAPTURED

The phase-wise external audit (Phases 18–20, findings **#206–#226** + the Phase-20 "no-trades" root-cause roadmap) is captured in its own file:

➡️ **[docs/AUDIT_PHASE18-20_TASKS.md](AUDIT_PHASE18-20_TASKS.md)**

Top-priority root causes for **trade generation** (verified against code): `DecisionAgent` over-gating, `MarketRegimeAgent` binary classification (no breakout state), and `ConfidenceEngine` bucketed (0/50/100) scoring. Note: the simulated-feed risk-block fixed in §2.1/§2.9 of this document is *also* a concrete zero-trade cause that the AI-only audit did not cover — verify the feed reads "Live" first.

---

## 6. Verification & deployment checklist

- [ ] Commit `fix/production-data-chain`; open PR; review.
- [ ] Run Flyway migrations on prod DB: `V1.9` (option LTP), `V1.10` (learning content), `V1.11` (snapshot OHLC/52-week).
- [ ] Confirm env on the VM: valid `GEMINI_API_KEY`, valid Angel One session (per credential-rotation note).
- [ ] First live session: watch logs for `"Angel One live quote -> …"` (FULL) or `"Angel One OHLC-mode index quote -> …"` (fallback) and for any `"dropping implausible … tick"` (offset issue).
- [ ] Verify on Dashboard: spot ≈ real Nifty, day high/low, prev close correct; 52-week populated (or "—" if OHLC-only — then schedule 4.4).
- [ ] Verify Option Chain LTP, Strategy Builder premiums, AI Signals firing, News real headlines, Reports multi-paragraph, Learning Center scroll + depth, Nifty candlestick chart.

---

_Backlog owner: update statuses as items land. Cross-reference: `MEMORY` → production-bugfix-data-chain, accuracy-improvement-roadmap, pre-launch-defect-fixing._
