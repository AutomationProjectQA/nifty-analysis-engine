# Trade Accuracy & Market-Coverage Plan

**Date:** 2026-06-28 · **Lens:** institutional options trading ([trading-platform-standards](.claude/skills/trading-platform-standards/SKILL.md))
**Goal:** higher win-rate, evidence-based signals, catch every tradeable move, 2% profit target.

> Method: 3 independent deep scans (indicator/analytics math · signal logic · market coverage)
> plus the existing defect audit. Findings below are grouped by how much they move the win-rate.

---

## 0. The uncomfortable truth first (read this before anything else)

Your **2% profit target is fine. The 40% stop-loss is what makes winning impossible.**

With target = +2% and stop = −40% (`application.yml` risk block), the break-even win rate is:

> 2·W = 40·(1−W) → **W = 95.2%**, *before* costs. After brokerage/STT/GST it's ~**96–97%**.

No options system — retail or institutional — sustains 96%. **One −40% stop erases ~20 winning
trades.** Every accuracy fix below helps the *hit-rate*, but **none can overcome this R:R** — the
engine's own `RiskAgent` already computes this setup as "unfavourable" and the code
**deliberately ignores it** ([DecisionAgent.java emit path] comment: "blocking would suppress all trades").

**You can keep "every trade targets 2%"** and still win — by making the **stop comparable to the
target** (e.g. target +2% / stop −2–3%, R:R ≈ 1:1 → break-even ~55%) or adding a **time/trailing
stop**. This single change matters more than all the signal logic combined. *(Flagged per the
project's own risk standard; not changing it without your go-ahead — but it must be stated.)*

---

## 1. 🔴 Accuracy killers — the analytics math is wrong (signals are partly noise)

These make the inputs to every signal unreliable. **Fix these first — they're the cheapest win-rate gains.**

| # | Issue | Where | Impact | Fix |
|---|-------|-------|--------|-----|
| A1 | **VWAP weights by *cumulative* daily volume**, not per-minute volume → afternoon prices get ~20× weight; "VWAP" just hugs the latest price. | `TechnicalIndicatorService.calculateVwap` | The spot-vs-VWAP factor (a confirmation in TechnicalAgent + ConfidenceEngine) is **meaningless**. | Use incremental per-period volume (the candle code already derives it: `total − volumeBefore`). |
| A2 | **Volume ratio also uses cumulative volume** → always >1, trends up all day. | `TechnicalIndicatorService.calculateVolumeRatio` (live path) | "Volume confirmed" (a required institutional gate) is fake. | Use per-candle volume (hourly path already does). |
| A3 | **EMA20/EMA50 are 20-/50-*minute* EMAs**, not the intended period — seeded off 1-min ticks. | `calculateEma` + `MarketCollectorService.collect` | The EMA20>EMA50 "trend structure" (drives TechnicalAgent, MarketRegimeAgent, direction) flips many times/day → noisy/false trend. | Compute EMAs off a proper candle series (5m/15m), not 1-min ticks; seed with an SMA. |
| A4 | **RSI is a 14-*minute* RSI** + thin seeding + boundary `IndexOutOfBounds` risk at exactly 14 bars. | `calculateRsi` | RSI zones (55–68 bullish, <40 bearish) fire on minute noise. | Compute on candles; warm up properly; fix the off-by-one. |
| A5 | **IV solver's vega is killed by in-pricer rounding** → `impliedVol` bails early for many strikes, silently falling back to 12.5. | `BlackScholesService.impliedVol`/`price` | The per-strike IV smile (B-H6) often doesn't actually compute → mispriced premiums in the builder/resolution. | Don't round inside the pricer the solver calls; bump σ relatively. |
| A6 | **PCR / Volume-PCR return 0.0 on no-data**, which downstream reads as **strongly bearish**. | `OptionsIndicatorService` + OptionsAgent/ConfidenceEngine | "No data" masquerades as a bearish confirmation → false PE bias. | Return a neutral sentinel and treat it as "unknown," not 0. |
| A7 | **Build-up (LBU/SBU/SC/LU) & OI-velocity use a spot proxy + a hardcoded 100k threshold** (ignores IV; 100k is huge for a thin strike, small for ATM). | `OptionsIndicatorService.detectBuildUp`, `OptionsAgent` | OI build-up (a required confirmation) mis-tags on IV-driven moves; velocity threshold not relative. | Use the option's own price change; make velocity relative (% of existing OI / z-score). |
| A8 | **Two divergent indicator implementations** (live 1-min path vs hourly-candle path) give different RSI/BB/MACD/volume for the same data. | `TechnicalIndicatorService` | Inconsistent signals; impossible to reason about. | One indicator path, candle-based, used everywhere. |

**Correct as-is (verified):** Max Pain algorithm, Black-Scholes price + normal CDF, ATR direction,
MultiTimeframe structure, ConfidenceEngine weighted-sum mechanics, hourly candle volume conversion.

---

## 2. 🟠 Signal logic — multi-factor in name, single-factor in practice

| # | Issue | Where | Impact | Fix |
|---|-------|-------|--------|-----|
| S1 | **No minimum-confirmation gate.** A trade can fire on 4 of 8 factors — and those 4 (Trend, MTF, VWAP, OI-ish) are **collinear** (all restate "spot is trending"). True orthogonal evidence (OI, PCR, futures) can be entirely missing yet still clear the 60 gate. | `ConfidenceEngine` linear blend; `DecisionAgent` gate | Fires trend-chasing setups with one real edge → the biggest false-positive source. **Violates the standard's "never one indicator / require trend+volume+OI+futures."** | Require ≥3 *orthogonal* groups: trend AND (OI build-up OR futures premium) AND (PCR not opposing). Collapse collinear trend/VWAP/MTF into one bucket. |
| S2 | **Direction decided by ONE tick.** CE/PE is set solely by `TechnicalAgent.analyze(latest)` (EMA+RSI+VWAP on the latest 1-min snapshot); then the whole ConfidenceEngine is direction-aware (`isCall ? x : 100−x`) so it **can only confirm, never dissent**. If the initial direction is wrong, the stack confidently confirms the wrong way. | `DecisionAgent` direction; `ConfidenceEngine` flips | High false-direction risk on the most important decision. | Make direction a **consensus**: TechnicalAgent + MultiTimeframeAgent + futures-premium sign + OI bias must agree; skip on disagreement. (MTF is currently only a confirmation of a direction it didn't help choose — backwards.) |
| S3 | **"Confidence" is not a probability.** Arbitrary 0/50/100 buckets + weighted points, blended on the same axis as a real ONNX probability. A "60" ≠ 60% win chance, so the gate is statistically meaningless. | `ConfidenceEngine`, `DecisionAgent` blend | Can't reason "trade only ≥70% setups." | **Calibrate to real P(win)** via logistic/Platt scaling — you already log per-factor scores (`SignalExplanation`) + outcomes (`TradeResult`). Gate on measured P(win) ≥ break-even + margin. **Highest-value upgrade.** |
| S4 | **Adaptive tuner chases noise.** Per-trade online update (LR 0.25) on collinear factors → reinforces whatever fires together; EXPIRED-marginal trades labeled as wins. Can drift to "trend maxed, everything floored" → *less* multi-factor confirmation. | `ConfidenceWeightTuner` | Likely **degrades** calibration over time. | Batch updates over ≥30 resolved trades with a held-out accuracy check; exclude marginal-EXPIRED labels; or replace with S3's periodic refit. |
| S5 | **Over-trades (retail, not institutional).** 3-leg ladder = 3 positions per edge; 50 trades/day; gate barely above the coin-floor; SIDEWAYS allowed with +8 instead of skipped. | `DecisionAgent` ladder; `application.yml` | High count × bad R:R × uncalibrated gate = many negative-expectancy trades. The standard says **"do not optimize for trade frequency."** | Single best-delta strike; far lower max-trades/day; skip (or range-trade) SIDEWAYS; A+ setups only. |
| S6 | **Futures-premium thresholds are absolute** (35/15 pts), ignoring that basis shrinks toward expiry → late-expiry always reads bearish for calls. | `ConfidenceEngine` futuresScore | False bearish bias near expiry. | Normalize basis by days-to-expiry. |

---

## 3. 🟠 Coverage — you cannot "catch every move" today

| # | Gap | Where | Impact | Fix |
|---|-----|-------|--------|-----|
| C1 | **Nifty only.** No Bank Nifty / FINNIFTY / MIDCPNIFTY / Sensex anywhere in the code. | clients, DTOs, DecisionAgent (`spot/50`) | **The single biggest recall gap** — Bank Nifty moves more than Nifty; every setup there is missed 100% of the time. | Add an `instrument` dimension (symbol, strike step, lot size, expiry rule); run the same pipeline per instrument. |
| C2 | **Long options only** (`BUY_CE`/`BUY_PE`). No selling, spreads, straddles/strangles, iron condor. | `DecisionAgent`, `TradeSignal` | Range days, high-IV mean-reversion, expiry/theta days, and defined-risk plays are **uncatchable** — NEUTRAL/SIDEWAYS regimes produce zero signals. | Regime-aware strategy selection: trending→long/debit-spread; sideways/high-IV→short straddle/strangle/iron condor; expiry→theta. Converts skipped regimes into covered ones *with defined risk* (coverage up, quality not down). |
| C3 | **1-minute cadence; tick stream wired to display only.** Decisions sample REST once/min; the WebSocket `MarketTickCache`/`OptionTickCache` are read only by the portal publisher, never by any agent. Intrabar breakouts/spikes are invisible. | `MarketScheduler`, `MarketCollectorService`, `AngelOneStreamClient` | Fast moves missed by design; an overrun cycle drops a whole minute (no backfill). | Feed ticks into a lightweight **event-driven trigger** (HTF break / VWAP cross / OI spike) that invokes full evaluation on demand; keep 1-min as backstop. Enable the stream for decisions. |
| C4 | **Higher-timeframe candles are reconstructed from 5-min samples**, and 5-min candles are built from 1-min spot samples (not true tick OHLC) → wicks/spikes under-captured. MTF agent ignores the real 15/30/60m candle tables. | `MultiTimeframeAgent`, `MarketCollectorService.updateCandle` | Breakouts of true HTF highs/lows are missed/false. | Build candles from ticks (true OHLC); have MTF read the actual 15/30/60m tables. |
| C5 | **No holiday calendar / feed-freshness gate.** Weekday+time only; no NSE holiday handling; can evaluate against a stale chain after a skipped cycle. | `MarketScheduler`, `DecisionAgent` | Spurious runs on holidays; decisions on stale data. | Add NSE/BSE holiday calendar + reject snapshots older than N seconds before evaluating. |
| C6 | **Blocking `collect()`** does HTTP + LLM + Telegram + order placement serially on the schedule thread → can overrun 60s and skip the next minute. | `MarketCollectorService.collect` | Dropped market windows. | Move LLM/Telegram/order off the cycle thread (async); keep the cycle lean. |

---

## 4. Prioritized roadmap (do in this order for max win-rate per unit effort)

**Phase 1 — make the inputs real (accuracy floor):** A1, A2, A3, A4 (candle-based VWAP/volume/EMA/RSI),
then A6, A5, A7, A8. *Without this, everything downstream is noise.*

**Phase 2 — make signals evidence-based (the standard):** S1 minimum-confirmation gate + de-collinearize,
S2 direction consensus, S6 basis normalization.

**Phase 3 — make confidence mean something:** S3 probability calibration (logistic on logged
factors→outcomes), then S4 batched learning. Add a proper **backtest + walk-forward** harness
(the standard requires it) to measure win-rate before/after each change.

**Phase 4 — catch every move (coverage):** C2 regime-aware strategies, C1 multi-instrument
(Bank Nifty first), C3 event-driven tick triggers, C4 true OHLC candles, C5/C6 freshness + async cycle.

**Phase 0 — the R:R (parallel, your call):** keep the 2% target, bring the stop in line (or trail).
Re-enable `RiskAgent` as a hard block once R:R is sane. *Highest single impact on net P&L.*

---

## 5. What "more accuracy" actually requires (institutional view)

1. **Correct inputs** (Phase 1) — a wrong VWAP/EMA poisons every signal.
2. **Orthogonal confirmation** (S1) — independent evidence, not one trend counted four times.
3. **Calibrated probability + measurement** (S3 + backtest) — you can't improve a win-rate you don't measure; add walk-forward validation so each change is proven, not guessed.
4. **Regime awareness** (C2) — the right *strategy* per regime beats forcing long-CE/PE into every market.
5. **Sane R:R** (Phase 0) — accuracy converts to profit only when one loss ≠ twenty wins.

> Honest bottom line: the architecture is solid, but today it's a retail-style, trend-collinear,
> uncalibrated long-options generator on Nifty-only data, running a structurally losing R:R.
> Fix the inputs, add a calibrated multi-confirmation gate, measure with a backtest, and broaden
> coverage by regime + instrument. That path — not lowering the gate or adding more strikes — is
> where the win-rate lives.
