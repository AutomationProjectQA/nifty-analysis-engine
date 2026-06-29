# Remediation Plan & Development Retrospective

> Compiled 2026-06-29. Inputs: the external audit (`docs/ENGINE_REVIEW_ALL_ISSUES.md`, `docs/AUDIT_PHASE18-20_TASKS.md`), the production fixes already made this session (`docs/ISSUES_AND_BACKLOG.md`), and direct code inspection.
> Read **Part A** (why) before **Part C** (the plan). Core symptom driving everything: **AI Signals generate (almost) no trades.**

---

## Part A — Why did this many issues accumulate? (honest retrospective)

The 226 audit findings + our own production bugs are **not random**. They cluster into **six systemic root causes**. Fixing the symptoms without fixing these will just regenerate them.

### R1. The system was built to *avoid bad trades*, not to *find good trades*
Risk philosophy was "maximum confirmation before trading." Over time, **independent hard gates were each added in isolation** — you can see the archaeology in the code comments (`P3-1` min-confirmation, `P3-3` direction consensus, `P5-1` strategy, `P5-5` freshness, session filter, liquidity floor, calibrated-probability gate…). Each gate was reasonable alone. **Nobody ever measured their cumulative effect.** The result is a funnel where every stage removes candidates until nothing survives (audit's central finding; verified in `DecisionAgent`).
- *Evidence:* `gating-threshold: 60` is the nominal bar, but the *effective* bar becomes `60 +8 (sideways) +10 (volatile) −15 (entry penalty) …` plus 3-of-4 consensus plus trend+flow+PCR confirmation plus liquidity ≥70. The compounding was never modelled.

### R2. Silent fallbacks hid failures instead of surfacing them
This is the **single biggest reason bugs survived undetected.** Throughout the codebase, when something failed it **returned plausible fake data and continued** as if healthy:
- Live market quote → hardcoded ~23,500 defaults, **still marked "Live"** (the index-quote bug we found this session).
- Option chain → flat simulated OI/IV.
- LLM reports/news → static template (the FII 450 Cr / GIFT boilerplate).
- Chart → TradingView silently fell back to Apple.
- Premiums → 0 when spot was 0.

Because nothing threw, **logs were clean and the UI looked alive** — so wrong data was indistinguishable from real data, for a long time. Graceful degradation without *honesty* (a visible "degraded" signal) is how a system lies to its own developers.

### R3. No observability — we couldn't see what the engine was doing
There was **no decision trace** (why was a candidate rejected?), **no live-vs-simulated indicator** until this session, and **no calibration monitoring**. With no window into the pipeline, issues couldn't be *noticed*, let alone measured. Development became "change something, deploy, hope" because there was no feedback signal.

### R4. Tests covered logic, not the real-world contracts
133 unit tests pass — yet the production-breaking bugs were all at the **boundaries**: Angel One's FULL-mode index rejection, the WebSocket binary frame offsets, TradingView's NSE licensing, Gemini's thinking-token budget. **None of these are exercised by unit tests** because they mock the broker/LLM/widget. The green test suite gave false confidence about a system whose failures live entirely in the integrations.

### R5. One god-class accreted all responsibility (DecisionAgent)
Features were bolted onto `DecisionAgent` rather than refactored into a pipeline. It now owns market/technical/options/risk/ML/calibration/signal-creation/order-exec/notification/persistence (audit #217). This made every change risky and **made the over-gating impossible to reason about or test in isolation** — which fed back into R1.

### R6. Config & thresholds sprawled; magic numbers everywhere
Thresholds (VIX 18, OI 50k, RSI bands, liquidity 70, score increments, premium fallbacks) are **scattered across many classes** (audit #218/#219/#222). There is no single place to see or tune "the policy," so the cumulative gating (R1) was invisible and every tweak risked side effects.

### Meta-conclusion
R1 (over-gating) explains *no trades*. R2 (silent fallbacks) + R3 (no observability) explain *why it wasn't caught*. R4 explains *why tests didn't help*. R5 + R6 explain *why it was hard to fix safely*. **The plan must address R2/R3 FIRST** — you cannot safely re-balance gates (R1) until you can see and measure what the engine rejects and whether its data is real.

---

## Part B — Guiding principles for the remediation

1. **Observability before surgery.** Make the engine's decisions and data-source visible *first*. Every later change must be measurable.
2. **Fail loud, not fake.** No path may return fabricated data while signalling success. Degraded = clearly flagged (chip + log + metric). (Partly done this session.)
3. **Establish a baseline, then change one thing.** Measure trade-candidate counts at each gate *before* loosening anything, so we know what each change buys.
4. **Don't fix all 226.** Most are enhancements. Sequence by *impact on trade generation*, then *risk reduction*, then *maintainability*, then *adaptive AI*.
5. **Respect the owner's risk choices.** The 2% target / ~20–40% stop and lot 65 are intentional — soften *gates*, don't silently change *risk parameters*.
6. **Verify at the boundary.** Add integration checks for the broker/LLM contracts that unit tests can't cover.

---

## Part C — Phased remediation plan

> Each phase has: **Goal · Tasks (→ finding refs) · Exit criteria (measurable)**. Phases are sequential; within a phase, tasks can parallelize.

### Phase 0 — Truth & Observability (do FIRST) 🔴 — ✅ IMPLEMENTED (not yet deployed)
**Goal:** make the engine's data-source and per-evaluation decisions visible and measurable. Without this we are blind.
- ✅ **DecisionTrace** — every evaluation persists {cycleId, instrument, time, direction, finalConfidence, effectiveGate, outcome, rejectStage, rejectReason, per-gate notes}. Entity + `V1.12` migration + repository; `DecisionAgent` records at every gate exit. Endpoints: `GET /api/v1/signals/decision-traces` and `GET /api/v1/signals/decision-funnel` (today's EMITTED vs REJECTED grouped by gate). Unit test asserts a risk-guard rejection is traced.
- ✅ **Gate funnel** — `decision-funnel` endpoint = candidates per reject stage (the "where do trades die" view), straight from DecisionTrace.
- ✅ **`decisionRunning` wedge fixed** — executor submission wrapped; guard released if submit throws (was a permanent hard-stop).
- ✅ **Data-integrity UNIQUE constraints** — `V1.13` dedupes + adds UNIQUE on `option_snapshot`, `market_snapshot`, `trade_result.signal_id`.
- ✅ **Startup health log** — `StartupHealthLogger` WARNs on blank Gemini key and on the `block-on-simulated-data` trade-block condition; logs provider/stream/market-hours.
- ⛔ Verify on the VM: live-feed honesty deployed (chip Live/Simulated), valid `GEMINI_API_KEY` + Angel One session. *(deploy step — yours)*
- **Exit (met in code):** you can answer from `decision-funnel` "how many candidates did each gate reject today", and from feed-status "is the feed live?"

### Phase 1 — Unblock trade generation 🔴 (the burning problem) — ✅ IMPLEMENTED (not yet deployed)
**Goal:** trades generate again, without abandoning capital preservation. All changes are config-gated and traced (Phase-0), so each is measurable/tunable.
- **Pre-req:** feed reads **Live** (else `RiskGuardService.block-on-simulated-data` blocks everything — fixed; verify on deploy).
- ✅ **Consensus loosened** (DA-F1/AG-F6): `min-direction-agreement` 3→2; vote now reports `participated`/4 in the trace. (2 agreeing voters + strictly beating the other side.)
- ✅ **`factor()` neutral default** (DA-F2): a missing confirmation factor now counts as 50 (neutral), not 0 (opposing) — the min-confirmation gate no longer fails closed on thin data.
- ✅ **Momentum → penalty** (DA-F6): opposing 1-min momentum applies `momentum-opposition-penalty:12` instead of a hard veto.
- ✅ **Continuous confidence scoring** (RC-S1/AG-F15): `ConfidenceEngine` RSI/VWAP/PCR/Futures are ramped (flag `nifty.confidence.continuous-scoring:true`); strong RSI no longer scored 0.
- ✅ **Regime continuous + warm-up + breakout** (AG-F1/F2/F3): `MarketRegimeAgent` returns a continuous trend score, falls back to spot-vs-VWAP while EMAs warm up, boosts on range breakouts, and no longer tanks the score on VIX>18 (avoids double-count with the critic).
- ✅ **Liquidity continuous + null-safe** (DA-F3/AG-F4): `LiquidityAgent` ramps with OI (config floor), removed the fake spread pass, unknown OI = neutral allow (no hard reject).
- ✅ **Calibration bar capped** (DA-F5): `calibration.max-required-winrate:0.65` so the ~0.91 break-even from the 2%/20% R:R can't fully close the engine. **Owner decision still needed on the R:R itself.**
- **Exit (met in code):** the fail-closed gates are softened and measurable; deploy, watch `/decision-funnel` move vs the Phase-0 baseline. Target a *sane* frequency, not max — tune `min-direction-agreement`/penalties from the funnel + win-rate.
- ⚠️ **Owner decisions pending:** the 2%/20% target/stop R:R (root of the calibration problem) and whether to raise consensus back to 3 once data shows quality.

### Phase 2 — Signal quality & diagnosability 🟠 — ✅ IMPLEMENTED (not yet deployed)
**Goal:** the trades that now generate are *good*, and we can debug them.
- ✅ `TechnicalAgent` now uses **MACD histogram + volume ratio + Bollinger width** in the rule score (audit #4/ML-P10-1), not just EMA/RSI/VWAP.
- ✅ `OptionsAgent` build-up **bias mapping corrected** (writer economics — call writing = bearish, put writing = bullish; AG-F8) and **strike step + ATM inferred from the chain** so it works for BANKNIFTY, not a hardcoded 50-grid (AG-F7). Window widened to ±2 steps.
- ✅ `LiquidityAgent` continuous + null-safe (done Phase 1). *Real bid/ask depth not available from the feed (only LTP) — removed the fake-spread pass; spread term dropped rather than faked.*
- ✅ **Collection-cycle id** (CS-P12-04): generated per cycle in the collector, threaded into `DecisionAgent.evaluateMarketForSignals(.., cycleId)` and the DecisionTrace, so collector logs ↔ decision trace correlate end-to-end.
- ✅ **Option-snapshot freshness guard** (DB-P16-3): `DecisionAgent` rejects (stage `stale_option_chain`) when the chain is older than `nifty.signal.max-option-staleness-seconds:300` vs the market tick.
- ✅ **WebSocket multi-record framing** (DB-P16-1): `routeFrame` now parses ALL records packed in a binary message (stride per mode: LTP 51 / Quote 123 / SnapQuote 379), with a safe single-record fallback — was dropping all but the first tick.
- ⛔ **Training/serving feature skew** (ML-P7-3): not addressed — needs retraining the model on engine-produced features (or aligning the serving feature computation). Larger ML task; ONNX is only weight-0.4, so quality-not-liveness.
- ⛔ Risk ATR/VIX-adaptive SL/TP (audit #10) — deferred; **owner decision** given the 2%/20% choice.
- **Exit (met in code):** every evaluation has a full decision trace with a cycle id; OI logic is correct + instrument-agnostic; stale option data can't drive a trade; live ticks aren't dropped.

### Phase 3 — Architecture & maintainability 🟡 — 🔄 PARTIALLY DONE (not deployed)
**Goal:** make future change safe (kills R5/R6).
- ✅ **DecisionAgent decomposed (the core SRP fix, audit #217/#224):** extracted `SignalEmissionService` — all emit/price/execute/persist/notify logic (the strike ladder, multi-leg, per-strike guards, order placement, signal+legs+explanation persistence, Telegram) moved out. `DecisionAgent` now only decides + traces and delegates via `signalEmissionService.emit(...)`. Removed ~8 emission-only deps + ~8 config fields from DecisionAgent. Logic moved verbatim (no behaviour change); tests migrated — gate tests stay in `DecisionAgentTest` (verify delegation), emission internals in new `SignalEmissionServiceTest`. 141 tests green.
- ⛔ Further split (`ValidationPipeline` / `SignalFactory` / `ExecutionPipeline` / `NotificationPipeline`) — not done; the emission extraction is the highest-value cut. Do the rest against a deployed baseline.
- 🔄 Centralize thresholds into one typed `TradingPolicy` config (#218/#219/#222) — STARTED: new `config/TradingPolicy` holds the cross-cutting, previously-DUPLICATED knobs (gating-threshold, target%, stop%) now read by `DecisionAgent`, `SignalEmissionService`, and `ConfidenceCalibrator` (target/stop were defined in all three). Remaining per-class tuning flags stay local; more knobs can migrate over time. 143 tests green.
- ⛔ Separate domain model `Signal → Order → Position → TradeResult` (#220) — not done.
- **Exit (partial):** DecisionAgent and SignalEmissionService are each independently unit-testable; the god-class is split. Remaining items are incremental.

### Phase 4 — Adaptive AI 🟡 — 🔄 STARTED (not deployed)
**Goal:** move from rule-engine-with-ML toward an adaptive engine (audit Phase 18).
- ✅ **Calibration monitoring (audit #209; T18-2):** `CalibrationMonitorService` + `GET /api/v1/signals/calibration` — buckets resolved trades by the confidence the gate saw (`Final_Confidence`) and reports realised win-rate per band vs the calibrator's modelled probability ("does predicted 80% actually win ~80%?"). Read-only; never affects trading. Tested.
- ✅ **Feature/factor-importance (#216; T18-6):** `FactorEffectivenessService` + `GET /api/v1/signals/factor-effectiveness` — avg factor score on wins vs losses ("edge") per factor, sorted; shows which confidence factors actually predict wins and which are noise to prune.
- ✅ **Drift detection (#212; T18-6):** `DriftMonitorService` + `GET /api/v1/signals/drift` — recent-vs-historical win-rate + confidence shift with a `degraded` flag (recency by `TradeResult.id`).
- ✅ **ML candidate re-scoring / rescue (#206; T18-1):** when a candidate is rejected at the confidence-SCORE gate, the ONNX model can RESCUE it if its directional probability ≥ `nifty.signal.ml-rescue-min-probability` (0.75). Default ON (`ml-rescue-enabled:true`). **Bounded:** overrides ONLY the scoring gate — the rescued candidate still must pass min-confirmation, calibration, and per-strike liquidity/exposure. Traced as `ml_rescue`. Tested both ways. **⚠️ This is the one gate-flow change that affects trade generation — watch `/decision-funnel` for `ml_rescue` notes after deploy and tune the threshold from win-rate.**
- ✅ **Replay harness (#215; T18-4):** `ReplayHarnessService` + `GET /api/v1/signals/replay-compare?start&end&candidateGate` — NON-persisting (unlike `BacktestingEngine`, which writes to live tables) A/B of the live policy vs a candidate gating threshold over a historical window. Reuses `BacktestingEngine.computeMetrics`. Structurally can't persist (no signal/result repos injected). Tested.
- ⛔ Paper-mode exploration (#211) — NOT done (ε-greedy sampling touches the decision path; compounds with ML-rescue — do after watching live).
- **Exit (mostly met):** engine fully observable + ML rescues + safe A/B replay available. Use the harness to validate the remaining gate-flow tuning.

### Cross-cutting — Prevent recurrence (start in Phase 0, sustain)
- **No silent fallbacks rule:** any fallback must set a `degraded` flag surfaced to UI + logs + a metric. Add a test that asserts simulated paths flag themselves.
- **Boundary/integration checks:** smoke tests or a `/health/live-data` check that hits the real Angel One quote and asserts a sane spot (catches R4-class bugs like FULL-mode index rejection).
- **Config centralization** (Phase 3) so cumulative gating is always visible.
- Keep the three docs in sync as items land.

---

## Part D — Fill the audit's gaps with our own code-verified review
The PDF left the **most trade-critical components summary-only or undelivered**: Phase 3 (DecisionAgent), 4 (MarketRegimeAgent), 5 (RiskGuardService), 6 (OptionsAgent), and 8–16 (Liquidity, MultiTimeframe, ML/ONNX, SignalGenerator, Collector, Scheduler, Repository, DB, Production stability). These overlap heavily with Phase-1/2 work. **Action:** during Phase 0/1, run a focused code-verified pass over these specific classes and append real, file/line-grounded findings to `ENGINE_REVIEW_ALL_ISSUES.md` so the picture is complete and trustworthy (not LLM-"plausible").

---

## Part E — Sequencing & first concrete steps
1. **Commit & deploy** the in-flight `fix/production-data-chain` branch (run migrations V1.9–V1.11); confirm the feed reads **Live**. *(This alone may restore some trading, since the simulated-feed risk-block was a hard blocker.)*
2. **Phase 0**: build the DecisionTrace + gate-funnel logging; capture a **baseline** (candidates per gate, trades/day).
3. **Phase 1**: with baseline in hand, soften gates + continuous confidence + breakout regime; measure the delta.
4. Proceed to Phase 2 → 3 → 4, measuring at each step.

> **Do not** start Phase 1 gate-loosening before Phase 0 observability + a confirmed Live feed — otherwise we repeat R1/R3 (changing gates blind).

---

## Part F — Effort & risk snapshot
| Phase | Rough effort | Risk if skipped |
|---|---|---|
| 0 Observability | S–M | Flying blind; can't measure any fix |
| 1 Unblock trades | M | The core symptom persists |
| 2 Signal quality | M–L | Trades generate but are low-quality |
| 3 Architecture | L | Every future change stays risky |
| 4 Adaptive AI | L | Engine stays static, slowly stale |

_Owner sign-off needed before changing any risk parameter (target/stop/lot) or enabling live order placement._
