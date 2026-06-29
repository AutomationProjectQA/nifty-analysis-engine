# Code-Verified Review — Trade-Critical Subsystems (fills audit Phases 3–6, 8–16)

> Compiled 2026-06-29 from **6 parallel code reviewers**, every finding grounded in real `file:line` on branch `fix/production-data-chain`. This is the code-verified companion to `docs/ENGINE_REVIEW_ALL_ISSUES.md` (which extracted the PDF but left these phases summary-only).
> Headline problem: **AI Signals generate (almost) no trades.** Severity: 🔴 critical · 🟠 high · 🟡 medium · 🟢 verified-OK.
> ID scheme: `DA-` DecisionAgent/signal/order · `AG-` analysis agents · `RC-` risk/confidence/calibration · `ML-` ONNX/technical features · `CS-` collector/scheduler · `DB-` repository/schema/stability.

---

## 1. THE TRADE-SUPPRESSION CHAIN (read this first)

There is **no single bug** — there is a chain of gates that mostly **fail *closed* on missing/thin data**. A candidate must clear ALL of these; each one independently rejects, and several reject on the *absence* of data rather than on a bad signal:

| Order | Gate | Why it blocks | Refs |
|---|---|---|---|
| 0 | **Simulated-data risk block** | `block-on-simulated-data: true` denies ALL trades whenever `DataFeedStatus.isLive(instrument)` is false — which is the default on cold-start/restart and after any broker fallback. | RC-R1, DA(F1 risk), CS-P12-02 — *partly fixed this session (index OHLC fallback + honest live); verify deployed.* |
| 1 | **Direction consensus ≥3 of 4** | MultiTimeframe abstains at 50 on cold candles and OI often NEUTRAL, so only 2 voters participate → 3 agreeing is impossible. Futures vote dropped entirely when `niftyFuture == null` (fail-closed). | DA-F1, AG-F6 |
| 2 | **Min-confirmation (trend AND flow AND PCR)** | `factor()` defaults a **missing factor to 0.0**, not neutral 50 → fails closed. PCR bucket `<0.8` → 0 instantly kills bullish. Redundant with gate 1 (OI/futures penalized twice). | DA-F2 |
| 3 | **Confidence ≥ effectiveGate** | Base gate 60 (yml) compounds with +8 sideways +10 volatile −15 entry → up to ~**93 effective**. Bucketed 0/50/100 factor scores + neutral-50 dilution rarely reach it. | DA§B, AG cross-cutting, RC-S1 |
| 4 | **Calibrated P(win) ≥ break-even** (once trained) | Break-even = stop/(stop+target) = 20/22 ≈ **0.91**. Demands a measured **91% win-rate** → blocks nearly everything after ~30 trades. Root cause: 2% target / 20% stop R:R. | DA-F5, RC-C2 |
| 5 | **Per-strike liquidity ≥70** | Spread is a hardcoded fake pass (always 0.5%), so score is binary 60/100 on `OI≥50,000`. Any strike under 50k OI (or null OI) is rejected → ladder collapses. | DA-F3, AG-F4 |
| 6 | **Momentum non-opposition** | Hard-returns on *any* opposing 1-min bias (no strength threshold) — reintroduces the single-tick noise the consensus was meant to remove. | DA-F6 |
| — | **Hard wedge** | If `decisionExecutor.execute()` ever throws, `decisionRunning` is never reset → **every future decision is skipped for the process lifetime.** | CS-P12-03 |

### Highest-leverage fixes to restore trade flow (do these first, measure each)
1. **Confirm the feed reads "Live"** (gate 0) — already addressed this session; the simulated-data block is the dominant hard blocker.
2. **Consensus**: `min-direction-agreement` 3→2, or require a majority of *participating* voters; don't drop the futures vote on null. (DA-F1, AG-F6)
3. **`factor()` default 0.0 → 50.0** and relax the PCR-neutral bucket in the min-confirmation gate. (DA-F2)
4. **Liquidity**: remove the fake spread pass; lower/scale the 50k OI floor or make liquidity advisory; treat null OI as "unknown, allow". (DA-F3, AG-F4)
5. **Continuous scoring** in ConfidenceEngine + MarketRegime (kill the 0/50/100 buckets; add BREAKOUT + warm-up fallback). (RC-S1, AG-F1/F2)
6. **Calibration/R:R**: the 91% break-even is unattainable — needs the stop brought toward the target (owner decision) or the gate capped. (DA-F5, RC-C2)
7. **Reset `decisionRunning` if executor submission throws.** (CS-P12-03)

> Note the recurring theme across every subsystem: **fail-closed on missing data** (null futures, absent factor → 0, null/zero OI → reject, cold EMA → neutral). When upstream data is thin, the engine defaults to "no trade" everywhere instead of "neutral/abstain."

---

## 2. DecisionAgent · signal emission · order execution (audit Phase 3 & 11)

- **DA-F1 🔴** Direction-consensus is the dominant trade-killer & fails-closed. 3-of-4 required; futures vote skipped when `niftyFuture==null`; neutral futures bucket shrinks the denominator; cold MTF abstains. `DecisionAgent.java:206-217, 608-634`. **Fix:** 2-of-4 or majority-of-participating; soft 0.5 for neutral futures.
- **DA-F2 🔴** Min-confirmation gate fails-closed: `factor()` defaults missing→**0.0**; PCR `<0.8`→0 kills bullish; redundant with DA-F1. `DecisionAgent.java:312-315, 641-655`. **Fix:** default 50.0; allow neutral PCR; fold into the vote.
- **DA-F3 🔴** Per-strike liquidity hard-gate fails-closed on null/zero OI; binary 60/100. `DecisionAgent.java:483-487` + `LiquidityAgent.java:17-41`. **Fix:** see AG-F4.
- **DA-F5 🟠** Calibration gate becomes ~91% win-rate bar once trained (from 2%/20% R:R). `DecisionAgent.java:320-330` + `ConfidenceCalibrator.java:68-82`. **Fix:** fix R:R or cap the bar.
- **DA-F6 🟠** Momentum confirmation hard-returns on any opposing tick (no strength threshold). `DecisionAgent.java:232-238`. **Fix:** make it a penalty / require strength.
- **DA-F4 🟠** `OptionsAgent` ATM hardcodes `/50` → wrong for BANKNIFTY (enabled). `OptionsAgent.java:55`. **Fix:** pass `spec.strikeStep()`.
- **DA-F7 🟡** `capital-per-order-percent: 100.0` contradicts its "safer default" comment. `application.yml:160-163`.
- **DA-F8 🟡** Entry-overextension penalty is bullish-only (asymmetric gating). `DecisionAgent.java:243-250`.
- **DA-F9 🟡** `historySufficient` latches permanently; once active, a flat-50 model blend can clip a 75 agent score under a raised gate. `DecisionAgent.java:119, 276-279`.
- **DA-F10 🟢 / verified-OK** RiskAgent advisory is correctly non-blocking (just noisy WARNs). No phantom-signal bug — FAILED order correctly prevents a phantom ACTIVE.
- **Verified clean:** no swallowed exceptions in the decision path; order/FAILED/SKIPPED handling is sound.

## 3. Analysis agents — Regime/Options/MultiTF/Liquidity/Timing/Market/Critic/Sentiment (audit Phase 4, 6, 8, 9)

- **AG-F1 🔴** MarketRegime trend is unreachable on cold start: EMA20≈EMA50 (SMA-seeded over short window) so strict `spot>ema20>ema50` rarely holds → NEUTRAL 50. `MarketRegimeAgent.java:80-88` + `TechnicalIndicatorService.java:45-61`. **Fix:** tolerance band/slope + warm-up fallback to spot-vs-ema20/vwap.
- **AG-F2 🔴** MarketRegime is effectively binary {85,15,50,40}; **no BREAKOUT** regime; SIDEWAYS and NEUTRAL both =50. `MarketRegimeAgent.java:52-91`. **Fix:** continuous score + BREAKOUT.
- **AG-F3 🔴** VIX>18 double-counted: regime drops trend to 40 AND CriticAgent subtracts −10 for the same VIX. `MarketRegimeAgent.java:52-55` + `CriticAgent.java:41-45`. **Fix:** one VIX treatment; consider raising the 18 floor.
- **AG-F4 🔴** LiquidityAgent: hardcoded simulated spread (always passes) + static 50k OI floor → real hard gate on fake data; rejects any strike <50k OI. `LiquidityAgent.java:14,33-37`. **Fix:** real spread or drop it; per-instrument/relative OI floor; or make advisory.
- **AG-F5 🟠** MultiTimeframe reads only the single latest bar (`close>open`) per TF — one doji flips a timeframe. `MultiTimeframeAgent.java:38-50`. **Fix:** trend over N bars / EMA slope.
- **AG-F6 🟠** MultiTimeframe returns 50 (abstains) on missing candles → consensus (gate 1) becomes unsatisfiable. `MultiTimeframeAgent.java:52-53`. **Fix:** real lean or fraction-of-available voters.
- **AG-F7 🟠** OptionsAgent & CriticAgent hardcode the NIFTY 50-pt grid / ±100 window → OI logic broken for BANKNIFTY. `OptionsAgent.java:55,62`, `CriticAgent.java:56,61,78`.
- **AG-F8 🟠** OptionsAgent build-up tally mislabeled (CE LONG_BUILD_UP counted as bearish) and biased to NEUTRAL. `OptionsAgent.java:60-81`. **Fix:** re-derive from writer economics + unit tests.
- **AG-F9 🟠** OI-velocity 5-min window rarely has ≥2 snapshots → component skipped. `OptionsAgent.java:102-131`.
- **AG-F10 🟠** CriticAgent penalties stack additively up to ~−95 (RSI−10, VIX−10, Event−30, opposing−15, wall−15); 100k OI-change floor is low for ATM NIFTY. `CriticAgent.java:31-101`. **Fix:** cap cumulative penalty (~−25); per-instrument floors; don't double-penalize a wall.
- **AG-F11 🟡** EntryTimingAgent bullish-only; razor-thin 0.15% retest band. `EntryTimingAgent.java:26-44`.
- **AG-F12 🟡** SentimentAgent FII is a static 450 Cr constant; on Yahoo failure all inputs fall back to neutral constants → Sentiment≈50 permanently. `SentimentAgent.java:30,96-102`. (Overlaps the news FII issue.)
- **AG-F13 🟡** MarketAgent momentum/premium thresholds are absolute points, asymmetric, not ATR/%-scaled. `MarketAgent.java:22-42`.
- **AG-F14 🟡** TechnicalIndicatorService warm-up returns neutral (RSI 50, EMA≈spot) until enough candles; ATR fallback magic `15.0`; `avgLoss==0 → RSI 100`. `TechnicalIndicatorService.java:69-103`.
- **AG-F15 🟡** ConfidenceEngine RSI band scores strong momentum (RSI 70+) as **0** for a CE *and* triggers the critic −10 → strong trends self-penalized twice. `ConfidenceEngine.java:86-92`. **Fix:** monotonic RSI up to ~75.

## 4. Risk · Confidence · Calibration · Weight tuning (audit Phase 5)

- **RC-R1 🔴** Simulated-data block fails CLOSED on cold-start & every restart; only set live after a successful broker fetch; any transient failure flips it false and silently suppresses all trades. `RiskGuardService.java:69` + `DataFeedStatus.java:40-42`. *(Dominant blocker; partly fixed this session — verify session-refresh flips it back to true.)*
- **RC-C2 🔴** Calibration logistic has no L2; once trained, the ~0.91 break-even (20%/2% R:R) over-blocks. `ConfidenceCalibrator.java:139-157` + `DecisionAgent.java:320-330`.
- **RC-C1 🟠** Calibration correctly fails OPEN when untrained, but the `Final_Confidence` join is fragile — missing explanation rows silently shrink samples & disable the gate with only an info log. `ConfidenceCalibrator.java:56-73`.
- **RC-R2 🟠** Daily-loss sum keys off `signal.signalTime`, not exit time → undercounts losses from positions opened the prior day. `RiskGuardService.java:84-89`.
- **RC-R3 🟠** Daily loss cap `2,500,000` is effectively disabled (unreachable) — a missing safety brake, not a blocker. `application.yml:189`.
- **RC-S1 🟡** Bucketed 0/50/100 factor scoring causes gate flicker near the threshold (1-pt RSI / 1-tick VWAP flips TRADE↔NO-TRADE). `ConfidenceEngine.java:84-118`.
- **RC-W1 🟡** Zero-weight collapse is defended (re-seed + 50.0 fallback), but a degenerate table silently yields 50 (<gate) = NO TRADE with no WARN. `ConfidenceEngine.java:67-78,195-198`.
- **RC-W2 🟡** Two weight tuners use inconsistent floors/rates (2.0/40 vs 5.0/normalized); per-trade tuner is OFF; active batched tuner is held-out-validated and safe. No collapse risk.
- **Cross-cutting:** the **2% target / 20% stop** (R:R ~1:10, break-even ~0.91) is the dominant systemic reason high-confidence signals can't clear the calibrated gate. Owner decision needed.

## 5. ML / ONNX · Technical features (audit Phase 7 & 10)

- **ML-P10-1 🟠** `TechnicalAgent.analyze()` scores only EMA/RSI/VWAP — **MACD, Bollinger width, volume ratio are computed but unused** in the rule path (only fed to ONNX at weight 0.4). `TechnicalAgent.java:37-87` + `TechnicalIndicatorService.java:443-445`. **Fix:** use them in scoring / a confirmation factor.
- **ML-P7-3 🟠** Training/serving feature SKEW: model trained on yfinance 1h bars w/ Wilder RSI & rolling windows; serving computes simplified short-window approximations from 1-min snapshots with constant cold-start fallbacks → model fed a different distribution (near-random). `train_model.py` vs `TechnicalIndicatorService.calculateHourlyFeatures:405-448`. **Fix:** align features or retrain on engine-produced features.
- **ML-P7-2 🟠** No feature scaling in the Java path — OK for the current tree model (scale-invariant), **fragile by contract** if retrained as logistic/NN. **Fix:** document, or bake a scaler into the ONNX graph.
- **ML-P7-1 🟡** Cold/missing model correctly bypassed (not a blocker), but a *runtime* inference failure returns a neutral 50 that is still blended at 0.4 → can clip a borderline signal. `OnnxModelService.java:109`. **Fix:** treat the 50 sentinel / an `inferenceFailed` flag like `!modelReady`.
- **ML-P10-2 🟡** `model-min-history:50` (snapshots) is fine for liveness but is the wrong granularity — `modelReady` can be true while hourly features are still cold (ties to P7-3).
- **ML-P7-4 🟢** No label leakage in training (clean walk-forward; `Target=Close.shift(-1)`).
- **Bottom line:** ONNX does **not** block trades; its issues are signal *quality*.

## 6. Collector · Scheduler (audit Phase 12 & 13)

- **CS-P12-03 🔴** If `decisionExecutor.execute()` throws (saturation / post-shutdown reject), `decisionRunning` is set true at dispatch and **never reset** → all future decisions skipped for the process lifetime. `MarketCollectorService.java:285-296`. **Fix:** try/catch the submit and reset the flag.
- **CS-P12-01 🔴** Frozen-feed staleness gate is effectively dead: every snapshot is stamped with `nowIst()` (server clock), not the broker/exchange tick time, so the duplicate clause never trips and staleness can't fire even on a frozen feed. `MarketCollectorService.java:305-313` + `AngelOneDataClient.java:318,404,752`. **Fix:** carry the broker quote timestamp; or detect frozen feed by comparing the price/OI payload.
- **CS-P12-02 🟠** Freshness gate doesn't consult `DataFeedStatus`, and first-cycle (null prevTime) bypasses staleness → simulated/degraded data can reach `dispatchDecision`. **Fix:** gate dispatch on `isLive()`.
- **CS-P13-01 🟠** IntradayEventTrigger runs `tryCollect()` *inline* on the shared 5-thread scheduler pool → a slow cycle stalls the 5s edge detector and starves the pool. `IntradayEventTrigger.java:43-79` + `application.yml` pool size 5. **Fix:** dedicated executor.
- **CS-P13-02 🟠** Trigger advances `lastSpot` even during cooldown / when `tryCollect()` is skipped → valid VWAP/breakout crossings are silently lost. `IntradayEventTrigger.java:64-78`. **Fix:** only advance baseline when a collection actually fires.
- **CS-P12-05 🟡** Async decision evaluates a *captured* snapshot; freshness is checked at dispatch, not at execution — a slow LLM/order can act on stale spot. `MarketCollectorService.java:280-298`.
- **CS-P13-03 🟡** Trigger uses global (non-instrument-scoped) VWAP/15m candles → wrong reference levels with BANKNIFTY enabled. *(same as DB-P16-6)*.
- **CS-P12-04 🟡** No collection-cycle id / correlation id anywhere → async decision logs can't be tied to the originating cycle (observability gap). **Fix:** per-cycle UUID in MDC.
- **CS-P13-04 🟡** Market-hours window includes the whole 15:30 minute; holiday calendar must be kept current.
- **Verified clean:** async-executor exceptions are logged ("Async decision evaluation failed"), not swallowed; `decisionRunning` reset in finally on the normal path.

## 7. Repository · Schema · Production stability (audit Phase 14, 15, 16)

- **DB-P16-1 🔴** WebSocket de-framing parses only ONE record per binary message (`routeFrame` reads offset 0) — SmartWebSocketV2 packs many ticks per frame → most live option/index ticks dropped or mis-parsed. `AngelOneStreamClient.java:286-301`. **Fix:** loop over the buffer in fixed strides per mode.
- **DB-P15-1 🟠** No UNIQUE on `option_snapshot(instrument,snapshot_time,strike_price)` / `market_snapshot(instrument,snapshot_time)` → duplicate rows from two trigger sources corrupt "latest snapshot" PCR/OI. **Fix:** add unique constraints / upsert.
- **DB-P15-2 🟠** `trade_result.signal_id` has no UNIQUE despite `@OneToOne` → double-resolution inserts 2 rows, `findBySignalId` throws/picks arbitrarily, `sumProfitLossSince` double-counts (corrupts the loss gate). **Fix:** `UNIQUE(signal_id)`.
- **DB-P16-2 🟠** `OptionTickCache.snapshot()` re-`get()`s after `keySet()` (non-atomic) and CE oi/ltp pair is written non-atomically → possible NPE on the push thread or torn rows. `OptionTickCache.java:27-55`. **Fix:** iterate `entrySet()`; update the pair atomically.
- **DB-P16-3 🟠** DecisionAgent/`OptionPremiumService`/`MarketController` trade & serve on the "latest" option snapshot with **no age validation** → stale option feed silently feeds signals/premiums/UI. `DecisionAgent.java:173-179`, `OptionPremiumService.java:41-43`. **Fix:** validate option-snapshot age vs `maxStalenessSeconds`.
- **DB-P16-4 🟡** Duplicate-position guard is check-then-act (no DB constraint / FOR UPDATE) — relies entirely on in-process flags; any 2nd instance/executor change reintroduces a double-open race. **Fix:** partial unique index on ACTIVE `(instrument,strike,signal_type)`.
- **DB-P16-5 🟡** N+1 in `send30MinSummary` (`findBySignalId` per trade). **Fix:** batch `findBySignalIdIn`.
- **DB-P16-6 🟡** IntradayEventTrigger global (non-instrument) snapshot/candle reads (= CS-P13-03).
- **DB-P16-7 🟡** `updateActiveTrades` reconstructs entry spot via global `findLatestBefore` → wrong instrument in multi-instrument DB. **Fix:** `findLatestBeforeByInstrument`.
- **Verified-OK 🟢:** `collect()` intentionally non-`@Transactional` (avoids pinning a pooled connection across HTTP); swallowed exceptions are benign (date-probe loop, heartbeat); **no secrets logged**, all creds from env vars (separate git-history leak tracked elsewhere); `.block()` usage acceptable (serialized worker + HTTP timeouts); nullability handled with guards.

---

## 8. De-duplication vs work already done this session
- The **simulated-feed / false-Live** root cause (gate 0, RC-R1, CS-P12-02) is the same family fixed on `fix/production-data-chain` (index OHLC fallback + honest Live chip + `update(instrument,…)`). **Still recommended:** gate `dispatchDecision` itself on `isLive()` and surface the cold-start denial at WARN.
- Two `DecisionAgent` hard gates (volatile-window, VWAP over-extension) were already softened to penalties this session — DA-F6 (momentum) and the consensus/confirmation/liquidity/calibration gates are **not yet** addressed.
- DB-P16-1 (multi-tick WS framing) is adjacent to the tick-offset verification already flagged in `ISSUES_AND_BACKLOG §4.1`.

## 9. Cross-cutting themes (the "why so many")
1. **Fail-closed on missing data** — the #1 reason for no trades (null futures, factor→0, null OI→reject, cold EMA→neutral).
2. **Binary/discrete scoring** everywhere (regime 85/15/50/40, MTF 90/10/50, liquidity 60/100, factors 0/50/100) → coarse jumps that rarely clear the gate + threshold flicker.
3. **Redundant/double penalties** — OI & futures consumed by both consensus and confirmation; VIX and RSI each hit two factors.
4. **Unviable R:R (2%/20%)** operationalized into a ~91% calibrated break-even gate.
5. **NIFTY-hardcoded assumptions** (50-grid) diverge from the spec-driven path → broken for BANKNIFTY.
6. **No observability** — no cycle id, no decision trace; failures (CS-P12-03 wedge, RC-C1 silent gate-disable) are invisible.
7. **Missing DB invariants** — no unique constraints → silent data corruption of the decision input and P&L.

## 10. Suggested sequencing (maps to REMEDIATION_PLAN.md)
- **Phase 0 (observability):** add decision-trace + cycle id (CS-P12-04); fix the `decisionRunning` wedge (CS-P12-03); add the missing UNIQUE constraints (DB-P15-1/2); confirm feed Live (gate 0).
- **Phase 1 (unblock):** consensus 3→2/participating (DA-F1/AG-F6); `factor()` 50.0 default (DA-F2); liquidity floor/advisory (DA-F3/AG-F4); continuous confidence + regime warm-up + BREAKOUT (RC-S1/AG-F1/F2); momentum→penalty (DA-F6); decide on R:R/calibration (DA-F5/RC-C2).
- **Phase 2 (quality):** TechnicalAgent uses MACD/BB/volume (ML-P10-1); real LiquidityAgent; OptionsAgent build-up fix + spec-driven ATM (AG-F7/F8); option-snapshot freshness (DB-P16-3); WS multi-tick framing (DB-P16-1); feature skew (ML-P7-3).
- **Phase 3+:** decompose DecisionAgent, central TradingPolicy config, then adaptive AI.

_All findings are read-only observations; nothing here is implemented yet (beyond the session fixes noted in §8)._
