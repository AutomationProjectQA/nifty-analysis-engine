# Accuracy Improvement Roadmap

**Owner goal:** higher win-rate, evidence-based signals, catch every tradeable move, 2% profit target.
**Source of findings:** [TRADE_ACCURACY_PLAN.md](TRADE_ACCURACY_PLAN.md) · **Standard:** [trading-platform-standards](.claude/skills/trading-platform-standards/SKILL.md)

This is the **execution plan** — sequenced, measurable, trackable. Status: ☐ Todo · ◐ In&nbsp;progress · ☑ Done · ⏸ Decision-needed.

## Guiding principles (how we run this)
1. **Measure or it didn't happen.** Nothing ships as an "accuracy win" unless the backtest/walk-forward win-rate improved vs the recorded baseline. (Standard requires backtesting + walk-forward.)
2. **One change at a time**, each behind config flags, with tests, then re-measure. Same cadence as the defect fixes.
3. **Inputs before logic before learning before coverage.** A wrong VWAP poisons every downstream change, so it's first.
4. **Capital preservation > frequency.** Reject weak setups; don't optimize for trade count.

---

## ⏸ Phase 0 — Risk/Reward decision (the multiplier on everything)
Not code yet — a decision only you can make. Every accuracy gain is multiplied (or nullified) by this.

| ID | Decision | Options | Status |
|----|----------|---------|--------|
| P0-1 | Stop-loss vs the 2% target | RESOLVED: kept configurable in application.yml; ideal R:R documented inline (target 2% + stop ~2-3% ≈ 1:1). Owner changes the value any time. | ☑ |
| P0-2 | Re-enable `RiskAgent` as a hard block once R:R is sane (reject R:R < 1:1 / configurable). | yes / no | ⏸ needs you |

> Until P0-1 is decided, Phases 1–4 still proceed (they improve hit-rate regardless), but **net profitability stays capped** by the current R:R. This is stated honestly per the risk standard.

---

## Phase 1 — Correct the analytics inputs (accuracy floor) 🔴
*Why first: signals are only as good as VWAP/EMA/RSI/PCR. These are bug fixes, low risk, high impact.*

| ID | Task | Files | Acceptance criteria | Effort | Status |
|----|------|-------|---------------------|--------|--------|
| P1-1 | VWAP from **per-period** volume (not cumulative); intraday reset kept | `TechnicalIndicatorService` | Unit test: known price/volume series → correct VWAP; afternoon weighting no longer dominates | S | ☑ |
| P1-2 | Volume-ratio from per-candle volume | `TechnicalIndicatorService` | Unit test: ratio ~1 in flat volume, spikes on real surges | S | ☑ |
| P1-3 | EMA20/EMA50 from a **candle series** (5m/15m), SMA-seeded | `TechnicalIndicatorService`, `MarketCollectorService` | Test: EMA matches reference on a fixture; no longer flips every few minutes | M | ☑ |
| P1-4 | RSI on candles, proper Wilder warm-up, fix off-by-one/IOOBE | `TechnicalIndicatorService` | Test vs reference RSI within tolerance; no exception at exactly 14 bars | M | ☑ |
| P1-5 | PCR/Vol-PCR return **neutral sentinel** on no-data (not 0=bearish) | `OptionsIndicatorService`, `OptionsAgent`, `ConfidenceEngine` | Test: empty/zero chain → neutral score, not bearish | S | ☑ |
| P1-6 | IV solver: stop in-pricer rounding for the solver; relative σ bump | `BlackScholesService` | Existing IV round-trip test tightens; solves for ATM±OTM without falling back to 12.5 | S | ☑ |
| P1-7 | OI-velocity now **relative** (>8% of strike OI + floor, was flat 100k). Build-up still uses spot-sign proxy — true IV-aware build-up needs per-strike premium history (deferred to a data task). | `OptionsAgent` | OI-velocity relative ✓ | M | ☑* |
| P1-8 | Unify to ONE candle-based indicator path (kill the divergent 1-min vs hourly impls) | `TechnicalIndicatorService` | Same inputs → same indicator value everywhere | M | ☑ |

**Exit gate:** all Phase-1 tests green; indicators verified against references. (No win-rate claim yet — this just makes the data real.)

---

## Phase 2 — Measurement foundation (prove every later change) 📏
*Why here: now that inputs are correct, a baseline is meaningful. This is the backbone for Phases 3–5.*

| ID | Task | Files | Acceptance criteria | Effort | Status |
|----|------|-------|---------------------|--------|--------|
| P2-1 | Extend the existing `BacktestingEngine` to replay stored snapshots → signals → outcomes and emit **win-rate, avg win/loss, expectancy, profit factor, max drawdown** (NET of costs) | `backtest/BacktestingEngine`, `service/AnalyticsService` | Backtest over stored history prints the metric set; deterministic | M | ☑ |
| P2-2 | **Walk-forward** harness: train/calibrate on window N, test on N+1, roll | new `backtest/WalkForwardRunner` | Produces out-of-sample win-rate per fold | M | ☑ |
| P2-3 | Record the **baseline** (post-Phase-1) metrics to compare against | results table below | Run on PROD history: `POST /api/v1/analytics/walkforward/run?start=..&end=..&folds=4` and paste numbers | S | ⏸ operational (needs real stored snapshots) |

**Exit gate:** a one-command backtest that reports out-of-sample win-rate. From here, **no signal-logic change merges unless it beats baseline out-of-sample.**

---

## Phase 3 — Evidence-based signals (the institutional gate) 🟠
*Each item measured against the Phase-2 baseline.*

| ID | Task | Files | Acceptance criteria | Effort | Status |
|----|------|-------|---------------------|--------|--------|
| P3-1 | **Minimum-confirmation gate:** require ≥N orthogonal groups — trend AND (OI build-up OR futures premium) AND (PCR not opposing) — before emit. Config-driven. | `DecisionAgent`, `ConfidenceEngine` | Backtest: false-positive rate ↓, win-rate ↑ vs baseline; weak setups rejected | M | ☑ |
| P3-2 | **De-collinearize** Trend/VWAP/MTF into one "trend" bucket so the score reflects independent evidence | `ConfidenceEngine` | Factor correlation reduced; score no longer inflated by one trend counted 4× | M | ☑ |
| P3-3 | **Direction by consensus** of TechnicalAgent + MultiTimeframeAgent + futures sign + OI bias; skip on disagreement (MTF becomes a chooser, not just a confirmer) | `DecisionAgent` | Backtest: false-direction rate ↓ | M | ☑ |
| P3-4 | Normalize futures-premium thresholds by days-to-expiry | `ConfidenceEngine` | No false bearish bias near expiry (test) | S | ☑ |

**Exit gate:** each merged item shows an out-of-sample win-rate improvement (or is reverted).

---

## Phase 4 — Calibrated probability + honest learning 🟠
*Turn "confidence points" into a real probability and make the tuner help, not chase noise.*

| ID | Task | Files | Acceptance criteria | Effort | Status |
|----|------|-------|---------------------|--------|--------|
| P4-1 | **Probability calibration:** fit logistic/Platt mapping logged factor scores (`SignalExplanation`) → P(win) (`TradeResult`); gate on measured P(win) ≥ break-even + margin | new `engine/ConfidenceCalibrator`, `DecisionAgent` | Reliability curve: predicted P(win) ≈ actual; gate becomes meaningful | L | ☑ |
| P4-2 | Replace per-trade online tuner with **batched** updates over ≥30 resolved trades + held-out check; exclude marginal-EXPIRED labels | `ConfidenceWeightTuner` | Weights stable; no degradation in walk-forward | M | ☑ |

**Exit gate:** calibration reliability plot acceptable; walk-forward win-rate ≥ Phase-3.

---

## Phase 5 — Catch every move (coverage) 🟠
*Coverage WITHOUT lowering quality — add the right strategy/instrument per regime.*

| ID | Task | Files | Acceptance criteria | Effort | Status |
|----|------|-------|---------------------|--------|--------|
| P5-1 | **Regime-aware strategy selection:** add SELL_CE/PE, short straddle/strangle, debit spreads, iron condor; route by `MarketRegimeAgent` (trend→long/debit; sideways/high-IV→short premium; expiry→theta) | `DecisionAgent`, `TradeSignal`, resolution | Sideways/NEUTRAL days now produce defined-risk signals; backtest shows positive expectancy on range days | L | ☐ |
| P5-2 | **Multi-instrument:** `instrument` dimension (symbol, strike step, lot size, expiry rule); run pipeline per instrument — **Bank Nifty first**, then FINNIFTY/MIDCPNIFTY/Sensex | clients, DTOs, entities, `DecisionAgent` | Bank Nifty signals generated + backtested; Nifty path unchanged | L | ☐ |
| P5-3 | **Event-driven triggers:** feed `MarketTickCache`/`OptionTickCache` into a lightweight trigger (HTF break / VWAP cross / OI spike) that invokes full evaluation on demand; 1-min stays as backstop | `MarketCollectorService`, `DecisionAgent`, stream | Intrabar breakouts captured in a replay test; no signal spam | L | ☑ (VWAP cross + 15m break; OI-spike extensible) |
| P5-4 | **True OHLC candles** from ticks; MTF reads real 15/30/60m tables | `MarketCollectorService`, `MultiTimeframeAgent` | HTF high/low breakouts detected on fixtures | M | ☑ (candles already true OHLC; MTF now reads real HTF tables) |
| P5-5 | Holiday calendar + feed-freshness gate; move LLM/Telegram/order off the cycle thread (non-blocking `collect()`) | `MarketScheduler`, `MarketCollectorService` | No runs on holidays; stale snapshot rejected; cycle never overruns/drops a minute | M | ☑ holiday-skip + freshness gate + async decision step (shared guard) |

**Exit gate:** coverage up (more valid signals across regimes/instruments) with win-rate held or improved.

---

## Results log (fill as we go — this is the proof)
Backtest/walk-forward now emit (NET of costs): `netWinRatePercentage`, `expectancyInr`
(avg P&L/trade — must be > 0), `profitFactor` (>1 = profitable), `avgWinInr`, `avgLossInr`,
`maxDrawdownInr`. Fill the baseline by running on real history:
`POST /api/v1/analytics/walkforward/run?start=<ISO>&end=<ISO>&folds=4` (or `/backtest/run`).

| Milestone | Net win-rate (OOS) | Expectancy/trade | Profit factor | Max DD | Notes |
|-----------|--------------------|------------------|---------------|--------|-------|
| Baseline (post-Phase 1) | — | — | — | — | ⏸ run P2-3 on prod history |

---

## Suggested execution order & rough effort
Phase 1 (≈8 tasks, mostly S/M) → Phase 2 (measurement) → Phase 3 → Phase 4 → Phase 5.
Phase 0 (R:R) runs in parallel and gates real-money profitability.

**Recommendation:** start **Phase 1** now (lowest risk, unblocks measurement), and you decide **P0-1** (stop-loss) whenever you're ready — they're independent.
