# External Audit — Phase 18–20 Task List (AI Architecture, Code Quality, Trade Generation)

> Source: external phase-wise review (Phases 18–20), findings **#206–#226** + the Phase-20 root-cause roadmap.
> Compiled 2026-06-29. Primary goal driving this work: **AI Signals generate (almost) no trades.**
>
> **Verification legend:**
> `✔ verified-in-code` — I confirmed the claim against this repo.
> `≈ plausible` — consistent with the code; not exhaustively re-confirmed.
> `🔄 partially-addressed` — work started on `fix/production-data-chain` this session.
> `💡 enhancement` — architectural recommendation, not a defect explaining today's behaviour.
>
> **Severity:** 🔴 critical · 🟠 high · 🟡 medium · 🟢 positive/no-action.
> Cross-reference: `docs/ISSUES_AND_BACKLOG.md` (production fixes already made this session).

---

## 0. Executive context (why no trades)

The audit's conclusion: **there is no single bug** — instead a *chain of conservative gates* progressively eliminates every candidate (Market → Regime → Technical → Options → Confidence → Liquidity → Risk → Decision). By the time `TradeSignal` is reached, almost nothing survives.

**Important complement from this session (not in the audit's AI-only scope):** the live-data path was also silently serving *simulated* data while `RiskGuardService` is configured with `block-on-simulated-data: true` — which **hard-blocks every new trade** whenever the feed is simulated. That was a *concrete* zero-trade cause and is fixed on `fix/production-data-chain` (see ISSUES_AND_BACKLOG §2.1, §2.9). **Verify the feed reads "Live" before concluding the gates are the only problem.**

This file tracks the *audit's* findings. Treat the Phase-20 "Top 15" as the action plan; Phases 18–19 are supporting detail.

---

## Phase 18 — AI Architecture Audit (findings #206–#216)

> Theme: the engine is a **hybrid rule-engine with ML augmentation**, not yet a self-learning policy engine. Most items here are **enhancements**, not defects causing zero trades — except where they compound the gating.

| # | Finding | Sev | Type | Note |
|---|---------|-----|------|------|
| 206 | **ML is advisory, not decision-driving.** Rules decide first; ONNX only blends at the end. If rules reject early, ML never gets to influence the outcome → good setups the rules discard can't be recovered. | 🔴 | ✔ verified-in-code | In `DecisionAgent`, direction/consensus/confirmation gates run *before* the ONNX blend; the model can only adjust an already-surviving candidate. |
| 207 | **Feedback loop tunes weights, not policy.** `ConfidenceWeightTuner` + `ConfidenceCalibrator` adjust factor weights only; the engine can't learn non-linear rules (e.g. "low liquidity + high trend = profitable"). | 🔴 | 💡 enhancement | Foundation is good; policy learning is a future layer. |
| 208 | **Rule engine & ML consume the same features** (RSI/EMA/VWAP/VIX/MACD/Volume) → correlation, not complementary intelligence. | 🔴 | ≈ plausible | ONNX features overlap `ConfidenceEngine`/`TechnicalAgent` inputs. Give ML *non-linear/interaction* features instead. |
| 209 | **No prediction-calibration monitoring.** Predictions are stored, but "predicted 80% → actual win-rate?" isn't measured continuously → confidence loses meaning over time. | 🔴 | ≈ plausible | `ConfidenceCalibrator` exists but ongoing reliability-curve monitoring/alerting is missing. |
| 210 | **Learning only from completed trades.** Ignores rejected signals, near-misses, never-taken opportunities, false negatives — all valuable training data. | 🔴 | ≈ plausible | Tie to #7/#12 (decision-trace persistence) — capture rejected candidates first. |
| 211 | **No exploration policy.** Always picks highest-confidence; borderline setups (e.g. conf 68) are rejected forever. Occasional sampling **in paper mode** would yield learning data. | 🟠 | 💡 enhancement | Add ε-greedy sampling gated to paper mode only. |
| 212 | **No concept-drift detection.** No monitoring of falling accuracy, shifting confidence distribution, or volatility-regime change → model silently goes stale. | 🟠 | 💡 enhancement | |
| 213 | **No ensemble governance.** Rules/ONNX/calibration blend is static; nothing decides "trust rules more today, ML more tomorrow." | 🟠 | 💡 enhancement | `modelWeight` is a fixed config value in `DecisionAgent`. |
| 214 | **Explainability stops at confidence.** No top-3 contributing features or counterfactuals ("what would change this prediction"). | 🟠 | ≈ plausible | Factor scores exist; add ranked contributions + counterfactual. |
| 215 | **No offline replay/backtest-on-current-engine framework.** Can't safely compare a new engine vs the current one on the last N days. | 🟠 | 🔄 partially | A `BacktestingEngine` exists — extend it into a replay/compare harness. |
| 216 | **No feature-importance tracking** over recent trades → can't prune ineffective signals. | 🟠 | 💡 enhancement | |

**Phase-18 positives (🟢 keep):** clean hybrid separation (rules/ML/calibration); confidence calibration present; weight-tuning service; explainable signals; graceful ML fallback.

### Phase-18 tasks
- [ ] T18-1 (🔴, from #206/#1): Let surviving-but-rejected candidates be *re-scored* by ML before final drop (candidate ranking, not first-gate-kill). **Highest trade-frequency impact.**
- [ ] T18-2 (🔴, #209): Add a calibration-monitoring job — bucket stored predictions, compute actual win-rate per bucket, log/expose a reliability curve.
- [ ] T18-3 (🔴, #210 + #7): Persist **rejected** candidates with their factor scores + rejection reason (decision trace) so learning has negative examples.
- [ ] T18-4 (🟠, #215): Extend `BacktestingEngine` into a replay harness (last 90 days → current vs candidate engine → compare).
- [ ] T18-5 (🟠, #211): ε-greedy exploration **paper-mode only** for borderline confidence.
- [ ] T18-6 (🟠, #212/#216): Drift + feature-importance tracking over the last N trades.

---

## Phase 19 — Code Quality & Architecture Audit (findings #217–#226)

> Theme: engineering quality is enterprise-grade, but `DecisionAgent` is becoming the bottleneck.

| # | Finding | Sev | Type | Note |
|---|---------|-----|------|------|
| 217 | **`DecisionAgent` violates SRP.** It orchestrates market/technical/options/risk/ML/calibration/signal-creation/order-exec/notifications/persistence. One change ripples everywhere; hard to test. | 🔴 | ✔ verified-in-code | Confirmed: single large class owns the whole pipeline. Split into `DecisionCoordinator → ValidationPipeline → SignalFactory → ExecutionPipeline → NotificationPipeline`. |
| 218 | **Business rules scattered** across `TechnicalAgent`, `OptionsAgent`, `MarketRegimeAgent`, `DecisionAgent`, `RiskGuardService` → thresholds spread everywhere. | 🔴 | ✔ verified-in-code | Centralise into a policy layer. |
| 219 | **Magic numbers in business logic** — e.g. VIX `18.0`, OI `50,000`, score increments `15`, premium fallback `150`, simulated spread `0.005`. | 🔴 | ✔ verified-in-code | E.g. `MarketRegimeAgent` VIX `18.0`; `ConfidenceEngine` RSI bands; option-chain window. Move to typed config objects. |
| 220 | **Domain model mixes analytics + execution** (`TradeSignal` holds both). Hard to evolve. Split `Signal → Order → Position → TradeResult`. | 🟠 | ≈ plausible | |
| 221 | **Weak domain boundaries** — services know repository/entity details directly. Introduce domain services between agents and repositories. | 🟠 | ≈ plausible | |
| 222 | **Business constants duplicated** (confidence/liquidity/ATR/RSI thresholds appear in multiple modules). One config/policy source. | 🟠 | ≈ plausible | Overlaps #218/#219. |
| 223 | **Inconsistent abstraction level** — some agents take `MarketSnapshot`, some `TechnicalFeatures`, some hit repositories directly. | 🟠 | ✔ verified-in-code | E.g. `MarketRegimeAgent`/`MultiTimeframeAgent` query repos themselves. |
| 224 | **Decision pipeline hard to unit-test** (big orchestrator, many deps). Smaller validators improve testability. | 🟠 | ✔ verified-in-code | Follows from #217. |
| 225 | **No domain events** — direct method calls instead of `MarketCollected → ConfidenceCalculated → SignalRejected → TradeExecuted`. | 🟠 | 💡 enhancement | |
| 226 | **Limited extension points** — adding a new confirmation source (e.g. `GammaExposureAgent`) needs edits across `DecisionAgent`, `ConfidenceEngine`, config. Plugin-style would scale. | 🟠 | ≈ plausible | |

**Phase-19 positives (🟢 keep):** good package layout; standard Spring Boot; constructor injection; modern Java (records/Optional/immutables); clear naming.

### Phase-19 tasks
- [ ] T19-1 (🔴, #217/#224): Decompose `DecisionAgent` into coordinator + validation pipeline + signal factory + execution + notification. Enables isolated tests and is prerequisite for safely re-balancing gates.
- [ ] T19-2 (🔴, #218/#219/#222): Introduce a single typed **TradingPolicy** config (all thresholds/magic numbers) injected where needed.
- [ ] T19-3 (🟠, #220/#221/#223): Separate analytics vs execution in the domain model; add domain services; standardise on one input abstraction per agent.
- [ ] T19-4 (🟠, #225/#226): Domain events + plugin registry for confirmation sources (long-term).

---

## Phase 20 — Final Engineering Audit (Top 15 root causes + roadmap)

> Scores: Architecture 8.7 · Code Quality 8.4 · Production-Readiness 7.9 · AI 8.5 · Strategy 6.8 · **Trade Generation 4.5** (weakest). 226 total findings.

### Top 15 verified root causes (priority order)

| Rank | Root cause | Stars | Verify | Action |
|------|-----------|-------|--------|--------|
| 1 | **`DecisionAgent` over-gated** — multiple early `return`s, independent hard gates, no retry path, no candidate ranking. *Biggest contributor to low trade frequency.* | ⭐⭐⭐⭐⭐ | ✔ + 🔄 | 🔄 Two hard gates already softened this session (volatile-window + VWAP over-extension → confidence penalties). **Still to do:** convert remaining hard gates to soft penalties + add candidate ranking (T20-1). |
| 2 | **`MarketRegimeAgent` rejects too many setups** — simplified sideways detection, **no breakout state**, binary classification. Valid early trends rejected. | ⭐⭐⭐⭐⭐ | ✔ verified-in-code | Add a BREAKOUT regime; make regime a *continuous* trend-strength score, not a discrete bucket (T20-2). (Sideways ATR factor already loosened to 0.10.) |
| 3 | **`ConfidenceEngine` uses bucketed scoring (0/50/100)** instead of continuous probability → confidence is lumpy/unstable, setups fall just under thresholds. | ⭐⭐⭐⭐⭐ | ✔ verified-in-code | Replace bucketed RSI/VWAP/PCR/Futures scores with continuous (sigmoid/linear) functions (T20-3). |
| 4 | **`TechnicalAgent` underuses engineered features** — MACD, Bollinger width, volume ratio are computed but mostly ignored in scoring. | ⭐⭐⭐⭐⭐ | ≈ plausible | Wire the computed features into the score (T20-4). |
| 5 | **`LiquidityAgent` is a prototype** — simulated spread, static OI threshold, no real order book → low scores can block strikes (min-liquidity-score 70). | ⭐⭐⭐⭐⭐ | ≈ plausible | Use real depth/quote data; make threshold config-driven (T20-5). |
| 6 | **Data synchronization** — no collection-cycle identifier across collectors/repositories/persistence. | 🟠 | ≈ plausible | Add a cycle id stamped on snapshot/option/candle rows. |
| 7 | **Decision trace missing** — engine can't explain *why* a candidate was rejected. | 🟠 | ✔ (no trace today) | Persist a per-evaluation decision trace (ties to T18-3). |
| 8 | **Repository freshness** — queries assume "latest = correct" without validating staleness. | 🟠 | 🔄 partially | `isFeedStale` exists in the collector; extend freshness checks to repo reads. |
| 9 | **Strategy optimized for certainty** (max-confirmation) instead of **positive expected value**. | 🟠 | 💡 | Reframe gating around EV. |
| 10 | **Risk model too static** — fixed SL/TP, no volatility adaptation. | 🟠 | ✔ verified-in-code | ATR/VIX-scaled SL/TP (note owner's intentional 2%/40% choice — coordinate before changing). |
| 11 | **Options model first-generation** — PCR/OI/Max-Pain only; missing IV surface, GEX, dealer positioning. | 🟠 | ≈ plausible | |
| 12 | **Production observability** — no decision traces / rejected trades / replay / feature persistence. | 🟠 | ✔ | Overlaps #7, T18-3, T18-4. |
| 13 | **AI learns weights, not policy.** | 🟡 | 💡 | = #207. |
| 14 | **DB lacks analytical history** — can't reconstruct rejected trades / feature vectors / factor contributions. | 🟡 | ≈ plausible | New tables for decision traces + feature snapshots. |
| 15 | **Decision pipeline hard to test.** | 🟡 | ✔ | = #224 / T19-1. |

### What the audit ruled OUT (don't chase these for "no trades")
ONNX integration · scheduler overlap protection · Spring wiring · repository impl alone · thread management · a single exception path. **(Caveat: independently of the audit, the simulated-feed risk-block *was* a real zero-trade cause — fixed this session.)**

### Estimated impact if fixed (directional, per audit)
- Remove/soften hard decision gates → **+40–60% more candidates**
- Improve regime classification → **+15–25%**
- Continuous confidence scoring → **+10–20%**
- Better liquidity model → higher execution quality
- Decision tracing → faster tuning/debugging
- Repo synchronization → more reliable signals

---

## Consolidated action plan (do in this order)

**🔴 Priority 1 — directly unblocks trade generation**
- [ ] T20-1 (#1/#217): Audit every `return` in `DecisionAgent.evaluateMarketForSignals`; convert hard gates to confidence penalties where safe; add candidate ranking instead of first-fail kill. *(2 of these already done.)*
- [ ] T20-2 (#2): `MarketRegimeAgent` → add BREAKOUT state + continuous trend-strength score.
- [ ] T20-3 (#3): `ConfidenceEngine` → continuous factor scoring (replace 0/50/100).
- [ ] **Pre-req check:** confirm dashboard shows **Live** (not Simulated) — `RiskGuardService.block-on-simulated-data` blocks all trades on a simulated feed (fixed this session; verify after deploy).

**🟠 Priority 2 — signal quality & diagnosability**
- [ ] T20-4 (#4): `TechnicalAgent` use MACD/BB-width/volume-ratio in scoring.
- [ ] T20-5 (#5): real `LiquidityAgent` (depth/quote); config-driven threshold.
- [ ] T20-6 (#6/#8): collection-cycle id + repo freshness validation.
- [ ] T20-7 (#7/#12/#14): persist decision traces + rejected candidates + feature vectors (new tables).

**🟡 Priority 3 — long-term**
- [ ] T19-1 / T19-2: decompose `DecisionAgent`; centralise `TradingPolicy` config.
- [ ] T18-1/T18-2/T18-4: ML candidate re-scoring, calibration monitoring, replay harness.
- [ ] AI adaptation (policy learning, drift, exploration), richer options model (IV/GEX), domain events/plugins.

---

## Status notes
- This file = **audit findings #206–#226 + Phase-20 roadmap**, captured for future work. **Nothing here is implemented yet** except the two `DecisionAgent` hard-gate softenings and the simulated-feed honesty fix already on `fix/production-data-chain`.
- Recommended next step (per the audit and this session): **do not attempt all 226** — execute Priority 1 first, measure trade frequency against a baseline, then proceed. Build T20-7 (decision traces) early so every later change is measurable.
- Keep `docs/ISSUES_AND_BACKLOG.md` (production bugs) and this file (architecture audit) in sync as items land.
