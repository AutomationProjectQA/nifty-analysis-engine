# Pre-Launch Audit — Nifty Analysis Engine

**Date:** 2026-06-25
**Scope:** Full QA + trading-logic correctness review of the portal (frontend) and the
automated options trading engine (backend), ahead of going live with real money.

## ⛔ Verdict

**DO NOT GO LIVE WITH REAL CAPITAL YET.** The backend has multiple *launch-blocking*
trading-correctness defects that will lose money or place invalid/phantom orders, and the
frontend will crash on real (partial) market payloads and can present **mock/stale data as
"Live"**. Fix all **Critical** items and re-audit before risking capital.

**Tally:** Critical 16 · High 18 · Medium 19 · Low 19

Severity = Critical (loses money / blocks launch) · High (wrong behaviour, fix before live) ·
Medium (incorrect/misleading, fix soon) · Low (polish / hygiene).

IDs: `B-*` = backend/trading, `F-*` = frontend/portal.

---

# PART 1 — BACKEND / TRADING LOGIC (pro-trader review)

## 🔴 Critical — launch blockers

| ID | Issue | Where | Impact |
|----|-------|-------|--------|
| **B-C1** | **Risk/Reward is structurally negative-expectancy.** Target +2% (`entry×1.02`) vs Stop −40% (`entry×0.60`) ⇒ R:R ≈ **1:20** ⇒ break-even win rate **95.2%** before costs. | `application.yml` risk block; `DecisionAgent.emitSignalForStrike` (target/SL calc) | No options scalper sustains 95% wins. One −40% stop erases ~20 winners. Guaranteed bleed. |
| **B-C2** | ~~Wrong lot size 65 vs 75~~ — **WITHDRAWN / NOT A DEFECT.** Per current NSE data the Nifty lot size **is 65**; the audit's "75" was an outdated reference. | `application.yml` `order-execution.lot-size: 65` | No change needed. *Recommended hardening:* read the lot size from the Angel One scrip master so it auto-updates whenever NSE next revises it (avoids this ambiguity entirely). |
| **B-C3** | **Silently trades on SIMULATED data while believing it's live.** On auth fail / API error / empty response the live client returns `getSimulatedFallback*` (random ~23,500 spot, fabricated OI) with no signal to the rest of the system. | `AngelOneDataClient` (auth + `fetchMarketData` + `fetchOptionChain` fallbacks) | Engine can generate signals (and later place real orders) from **invented prices**. Catastrophic. The live client must **abort trading** on failure, never substitute fake data. |
| **B-C4** | **`fetchLtp` returns a hardcoded 150.0 on failure** (not a sentinel) → fake premium flows into entry, SL/target, order price, and resolution. | `AngelOneDataClient.fetchLtp`; `OptionPricingService`; `OrderExecutionService` | Live trade entered at fake ₹150; LIMIT order at 150 into a market where the option is 80 or 300 → rejected or filled wildly wrong. Return ≤0 on failure and abort. |
| **B-C5** | **Trade resolution uses a flat 0.5 delta for all strikes** when LTP unavailable (`current = entry ± spotMove×0.5`); ignores theta. Worse now that the strike **ladder adds ITM (~0.65) / OTM (~0.35)** legs. | `MarketCollectorService.updateActiveTrades` | SL/target mis-trigger; reported P&L diverges from reality; the **adaptive weight learner trains on fabricated outcomes**. Use Black-Scholes (already built) or mark "unknown" instead of resolving. |
| **B-C6** | **Strike ladder fires up to 3 orders/signal, each sized to 100% of wallet** (`capital-per-order-percent: 100.0`); no aggregate position/capital cap. | `DecisionAgent` ladder loop; `OrderExecutionService.calculateQuantity`; `RiskGuardService` | 300% intended notional / 3× over-leverage on one directional view. Allocate capital across the ladder + add a portfolio exposure cap. |
| **B-C7** | **Live P&L ignores ALL costs** — brokerage, STT, exchange/SEBI fees, GST, stamp duty, slippage. (`profitLoss = (exit−entry)×qty`.) Cost model exists only for backtest, not live. | `MarketCollectorService.updateActiveTrades` | On a +2% target, round-trip costs often exceed the gross gain → "winners" are net losers; the daily-loss kill-switch under-counts real losses. |
| **B-C8** | **`RiskAgent` (the only R:R / high-VIX gate) is dead code — never called** in the signal pipeline. | `RiskAgent` (unreferenced) | The component that would catch B-C1 and VIX premium-decay never runs. Wire it in and hard-reject poor R:R. |

## 🟠 High

| ID | Issue | Where | Impact |
|----|-------|-------|--------|
| **B-H1** | Option expiry symbol built from **server-TZ** `LocalDate.now()`/hour, not IST; symbol rebuilt every cycle instead of stored. | `AngelOneDataClient.findCurrentOptionExpiryDateSymbolStr`; `OptionPricingService` | Near Thursday rollover the wrong weekly contract is priced/resolved → LTP misses → silent 0.5-delta proxy. Persist the exact tradingsymbol/token on the signal. |
| **B-H2** | **Pervasive server-TZ vs IST mismatch.** `signalTime`, candles, `holdingTime` use `LocalDateTime.now()`; risk "start of day", session windows, market hours use IST. | `MarketCollectorService`, `DecisionAgent`, `RiskGuardService`, `MarketScheduler` | If server ≠ IST: daily trade-count & daily-loss limits compute wrong (kill-switch bypassable); `holdingTime` can be negative; candles misalign. Use one timezone everywhere. |
| **B-H3** | **Scheduler can overlap; `@Transactional collect()` wraps blocking external HTTP** (Angel One, LLM, Telegram, order placement). | `MarketScheduler`; `MarketCollectorService.collect` | A DB connection (pool max 10) is pinned across slow HTTP; overlapping minute ticks exhaust the pool and can **double-generate orders** (duplicate guard isn't concurrency-safe). Move HTTP out of the transaction; add ShedLock + a unique constraint on `(strike, signalType, ACTIVE)`. |
| **B-H4** | **Phantom positions:** signal is saved ACTIVE + Telegram sent *before* `executeOrder`; order failure/early-return doesn't propagate; no `orderId` stored, no broker reconciliation. | `DecisionAgent.emitSignalForStrike`; `OrderExecutionService` | P&L, loss limits and the learner accumulate on trades that were never placed. Only mark ACTIVE after a confirmed fill; store orderId; reconcile vs broker book; add idempotency key. |
| **B-H5** | Live bracket order sends SL/target point-offsets on a **LIMIT at a stale/fake entry**; no tick-alignment, partial-fill, or gap handling. | `OrderExecutionService` | LIMIT may not fill (phantom) or offsets are nonsensical vs real fill; gap-throughs blow past the −40% stop unmodeled. Use marketable-limit w/ fresh LTP, confirm fill + avg price. |
| **B-H6** | **IV skew ignored.** Live path hardcodes IV = 12.5 for every strike (CE & PE); BS uses one IV for both legs. | `AngelOneDataClient` (IV stamps); `OptionPremiumService`; `BlackScholesService` | All BS premiums (payoff builder, any BS valuation/resolution) mispriced; ignores the Nifty smile. Parse real per-strike/per-type IV. |
| **B-H7** | **OI-change = 0 on the first cycle after every restart** (`lastOiMap` is in-memory, not persisted). | `AngelOneDataClient` (OI-change calc) | Critic "heavy writing" penalties & OI-velocity gates don't apply right after a deploy — exactly when most exposed. Seed OI baselines from DB or use broker's OI-change field. |

## 🟡 Medium

| ID | Issue | Where | Impact |
|----|-------|-------|--------|
| **B-M1** | **Target1 stored but never used** for resolution/partial exit (only SL & T2 checked). | `DecisionAgent`; `MarketCollectorService.updateActiveTrades` | The "two-target" design is illusory; no scaling out, worsening B-C1 expectancy. |
| **B-M2** | **Risk limits effectively disabled:** 100% capital/order, 50 trades/day, daily-loss cap ₹10,00,000. | `application.yml` risk block | All-in per order; kill-switch never realistically trips. Set sane fractions; validate vs wallet at startup. |
| **B-M3** | Resolution only at exact thresholds; **gaps / "both hit" / EOD-expiry square-off not handled** (`EXPIRED` outcome never produced). | `MarketCollectorService.updateActiveTrades` | Options held to expiry expire worthless unbooked; minute sampling misses real SL touches. Add mandatory expiry square-off + gap handling. |
| **B-M4** | Entry spot **reconstructed** via `findLatestBefore(signalTime)` instead of stored → can pick wrong/stale snapshot (compounded by B-H2). | `MarketCollectorService` | Wrong entry anchor → wrong proxy P&L. Store `entrySpot` + entry LTP on the signal at creation. |
| **B-M5** | Sideways "extra gate" weak/inert (`sideways-atr-factor: 0.10` rarely flags sideways; regime doesn't feed confidence). | `DecisionAgent`; `MarketRegimeAgent` | The documented sideways protection is mostly inactive. Feed regime into confidence; tune ATR factor empirically. |
| **B-M6** | Online weight tuner learns from **fabricated outcomes (B-C5) and phantom trades (B-H4)**; LR 0.5 can skew the distribution. | `ConfidenceWeightTuner` | Optimizes toward noise/fake P&L. Only learn from broker-confirmed, cost-adjusted real outcomes; lower LR; regularize to priors. |
| **B-M7** | **Telegram label says "Stop Loss (2%)" while real SL is 40%.** | `OrderExecutionService` (message strings) | Operator sees false risk-per-trade. Fix the label. |
| **B-M8** | `count()` every cycle + N+1 lookups in hot paths grow slower with data, lengthening the transactional cycle (feeds B-H3). | `DecisionAgent`, `RiskGuardService`, `MarketCollectorService` | Performance degradation under load. Cache counts; batch/aggregate queries. |

## 🟢 Low
- **B-L1** Hardcoded fake client IP/MAC in every broker request — Angel One may flag/limit.
- **B-L2** `loadScripMaster` failure seeds only spot+VIX → all option/future lookups null → silent "no trades" while appearing healthy. Add readiness gate + alert.
- **B-L3** TOTP uses only the current 30s window (no ±1 step) → clock skew → intermittent auth fail → silent simulated fallback (B-C3).
- **B-L4** Weekly-expiry assumed Thursday in two places — **verify current NSE rule** before launch; wrong expiry → wrong symbol → no fills.
- **B-L5** JWT never refreshed; Angel One tokens expire (~24h) → mid-session all calls fail → silent fallback. Add expiry/refresh.
- **B-L6** Broad `catch (Exception)` in `collect()` + unchecked Map casts hide failures; a shape change silently aborts a cycle.
- **B-L7** Failed cycle logs but emits no health/alert signal that data went stale.
- **B-L8** Black-Scholes floors T at 1 day, no dividend yield; minor tail error from the norm-CDF approximation on expiry day / deep wings.

---

# PART 2 — FRONTEND / PORTAL (QA review)

## 🔴 Critical

| ID | Issue | Where | Impact |
|----|-------|-------|--------|
| **F-C1** | **Dashboard crashes on any missing market field.** State is *replaced* with the raw payload, then `marketData.niftySpot.toLocaleString()`, `.indiaVix.toFixed()`, `.rsi.toFixed()` etc. throw if a field is absent (common pre-market/partial snapshot). | `Dashboard.jsx` (fetch + render) | Live backend returning a partial snapshot **blanks the page** (ErrorBoundary). Ironically works in demo mode (full mock). Merge into defaults or guard every access. |
| **F-C2** | Tick handler overwrites good values with `undefined` (a tick is partial). | `Dashboard.jsx` `/topic/tick` handler | Corrupts spread/VIX and triggers F-C1 crash. Only overwrite present fields (`?? prev.x`). |
| **F-C3** | OptionChain renders `row.ceOi/100000`, `row.peOi/...`, `s.strikePrice.toString()` with no `|| 0` guard. | `OptionChain.jsx` (chart map + table) | Missing fields → "NaN L", "NaNk" chips, duplicate/undefined React keys, or crash. |
| **F-C4** | `spotPrice.toLocaleString()` / `Math.round(spotPrice/50)` crash/NaN if market payload lacks `niftySpot`. | `OptionChain.jsx` | Page crash / NaN ATM. Guard before formatting. |
| **F-C5** | AiSignals renders `sig.entry.toFixed()`, `.target1`, `.stopLoss`, `.confidence`, `value={sig.confidence}` and `new Date(sig.signalTime)` unguarded. | `AiSignals.jsx` | One malformed signal blanks the page or shows "Invalid Date". Guard each. |
| **F-C6** | **Mock data shown as "Live."** WS callbacks call `setLive(true)` unconditionally on every frame (even empty), so the "Demo data" chip disappears while **mock option chains / mock BUY-CE/PE signals remain on screen**. | `OptionChain.jsx`, `AiSignals.jsx` WS handlers | A trader sees fabricated signals labeled live — the most dangerous portal defect. Only set live when real data was applied; track `dataSource` explicitly. |
| **F-C7** | REST sets `live=true` even when it *kept mock* because the live array was empty. | `OptionChain.jsx` fetch | "Backend up but empty" is mislabeled as live with mock strikes shown. |
| **F-C8** | Global status chip says **"Live • Streaming"** based only on `/health` 200 + socket open — not on real market frames; can directly contradict a per-page "Demo data" chip. | `App.jsx` status chip; `useBackendStatus` | Health + open socket ≠ live market data (feed can be down while server is healthy). Drive from last-frame timestamp. |

> **Cross-cutting (both layers):** the **simulated-fallback-shown-as-live** problem is the same root issue as **B-C3** on the backend. Fixing it needs a backend `dataSource: LIVE/SIMULATED` flag surfaced to the UI.

## 🟠 High

| ID | Issue | Where | Impact |
|----|-------|-------|--------|
| **F-H1** | StrategyBuilder **"unlimited loss" detection is dead** (`... && false`). Naked shorts / short straddle show a finite max-loss. | `StrategyBuilder.jsx` `computeMetrics` | Material risk mis-disclosure: unbounded loss shown as a finite number. Remove `&& false`; treat still-falling edges as unlimited. |
| **F-H2** | Payoff window only ±10% of spot → max P&L / breakevens wrong/missing for wide strategies (iron condor/strangle legs at ±4 steps). | `StrategyBuilder.jsx` payoff calc | Understated risk/reward for spreads. Widen domain or compute analytically. |
| **F-H3** | Lots input accepts empty/NaN/decimal/0 → fractional/impossible lots in P&L. | `StrategyBuilder.jsx` lots field | Wrong P&L; can't clear field. Coerce to positive integer. |
| **F-H4** | Calculators: every number field does `Number(value)` → clearing → `NaN` propagates ("₹NaN", "NaN Lots"); negatives accepted. | `Calculators.jsx` (all inputs) | Nonsensical outputs. Validate/clamp; default NaN→0; `min:0`. |
| **F-H5** | Calculators R:R wrong for inverted prices (SL above entry → negative risk, ratio "1:0.00"). | `Calculators.jsx` | Misleading risk numbers. Validate ordering target>entry>SL. |
| **F-H6** | Position-size **division by zero** when Stop-Loss Points = 0 → "Infinity Lots / Shares / Capital". | `Calculators.jsx` `maxLots` | Absurd output. Guard `stopPoints > 0`. |
| **F-H7** | Brokerage calc invents a flat **₹1.5/order "NSE Transaction Tax"**; STT/SEBI/GST/stamp omitted; false precision. | `Calculators.jsx` | Materially wrong costs presented authoritatively. Compute real %-of-turnover or relabel as rough estimate. |
| **F-H8** | LearningCenter PCR article shows **raw LaTeX** (`$$...$$`) — no `remark-math`/`rehype-katex`. | `LearningCenter.jsx` + any `$$` content | Broken formula display. Add math plugins + KaTeX CSS, or strip math. |
| **F-H9** | LearningCenter **silently keeps mock articles with no indicator** (no demo chip, no error state); empty backend keeps mock too. | `LearningCenter.jsx` fetch | Users read fabricated content believing it's the real library. Add demo/empty/error states. |
| **F-H10** | NewsIntelligence "Generate now" failure is **completely silent** (console only). | `NewsIntelligence.jsx` | Spinner stops, nothing changes — looks like a no-op. Surface success/error (MarketReports does it right). |
| **F-H11** | NewsIntelligence mislabels/crashes on malformed item (`new Date(null)`, missing `id` keys, undefined summary). | `NewsIntelligence.jsx` | "Invalid Date", key warnings, blank chips. Guard. |

## 🟡 Medium
- **F-M1** WebSocket client is a module singleton, `activate()`d but **never `deactivate()`d** (socket + reconnect loop live forever). Ref-count subs.
- **F-M2** `useStreamConnected` initial-state edge cases (minor; mostly handled).
- **F-M3** OptionChain ATM uses hardcoded `/50` step; if chain step differs, no row gets the ATM chip (StrategyBuilder derives step correctly — copy that).
- **F-M4** Dashboard/OptionChain show realistic **mock numbers with no loading state** before first response (fabricated data flashes as real). Add skeleton while `live===null`.
- **F-M5** Performance: no max date-span (multi-year range → 60s timeout → generic failure); `winRatePercentage` renders `undefined%` if absent.
- **F-M6** Performance "Target Hits" = `target2Hits` only, ambiguous vs backtest's "Target1 Touches".
- **F-M7** AI markdown rendered without sanitization layer (safe by default today; flag for security pass); `#`/`##` headings unstyled.
- **F-M8** AiSignals "EXPIRED" tab actually means "not ACTIVE" — includes TARGET/STOP_LOSS resolutions. Relabel "Closed/Resolved".
- **F-M9** Demo chip can get **stuck on after recovery** (depends on a WS frame arriving; no REST re-poll) — flip side of F-C6.
- **F-M10** Dashboard pivot levels labeled "standard daily pivots relative to yesterday close" but are arbitrary ±0.5%/1.2% bands off **live spot**. Copy is false. Implement real pivots or fix the text.
- **F-M11** Dashboard VIX/trend treat `undefined` as the lowest bucket silently (also crashes first via F-C1).

## 🟢 Low
- **F-L1** AdSense `setTimeout` not cleared → state update after unmount (warning noise).
- **F-L2** AdSense fallback is a clickable "Sponsored Advertisement" linking to **Google AdSense signup** — end users see a fake ad that's an internal setup prompt. Hide in prod.
- **F-L3** OptionChain row hover is white-on-white (dark-theme leftover) — invisible. Use `action.hover`.
- **F-L4** Chart X-axis strike ticks collide on mobile (`interval`/angle not set).
- **F-L5** A11y: leg-delete `IconButton` has no `aria-label`; clickable ad `Box` isn't keyboard-reachable; bullish/bearish encoded by color only.
- **F-L6** Inconsistent `minimum/maximumFractionDigits` across pages → varying decimal places.
- **F-L7** Option-profit calc labeled "ESTIMATED NET PNL" but is **gross** (no costs).
- **F-L8** ErrorBoundary leaks raw error strings (`...'toFixed'`) to users in prod.
- **F-L9** `useBackendStatus` requests not aborted on cleanup (redundant in-flight calls).
- **F-L10** StrategyBuilder empty-premiums path yields string strikes (`'' + 100`) that won't match the chain; disable the builder with a message instead.
- **F-L11** SIP calc accepts negative/absurd returns silently.

---

# Must-fix-before-live checklist (the blockers)

**Backend (trading):**
1. **B-C1 + B-C8** — fix the 1:20 R:R (and actually wire `RiskAgent`). This is the #1 money-loser.
2. ~~**B-C2** — lot size~~ — withdrawn; 65 is the current NSE Nifty lot size. (Optional: read it from the scrip master to auto-track future NSE revisions.)
3. **B-C3 + B-C4** — never trade on simulated data / 150.0 LTP; abort + alert on degraded feed.
4. **B-C5** — don't resolve real trades on a flat-0.5-delta proxy; use BS or mark unknown.
5. **B-C6 + B-M2** — cap per-order capital and total concurrent exposure across the ladder.
6. **B-C7** — model real costs (STT/brokerage/GST/stamp/slippage) in live P&L.
7. **B-H3 + B-H4** — move HTTP out of the DB transaction, prevent scheduler overlap, only mark ACTIVE on a confirmed fill, reconcile vs the broker book.

**Frontend (portal):**
8. **F-C1…F-C5** — guard every `.toFixed`/`.toLocaleString`/array access against partial API payloads (these crash live pages).
9. **F-C6…F-C8** — stop labeling mock/empty/stale data as "Live"; introduce an explicit `dataSource` flag surfaced from the backend.
10. **F-H1** — re-enable unlimited-loss in the payoff builder; **F-H4/F-H6** — fix Calculator NaN/Infinity.

---

*Audit method: two independent automated reviewers (frontend QA + trading-logic) plus issues
found during development. Line numbers may drift slightly as files change — search by symbol.
Report-only; no code was modified.*
