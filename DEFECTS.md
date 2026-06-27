# Defect List — Nifty Analysis Engine

Trackable defect log. Detailed write-ups + fixes in [PRE_LAUNCH_AUDIT.md](PRE_LAUNCH_AUDIT.md).

**Severity:** 🔴 Critical (blocks launch) · 🟠 High · 🟡 Medium · 🟢 Low
**Status:** Open / Fixed / Won't&nbsp;Fix / Not-a-defect / Acknowledged / Mitigated
**Totals (2026-06-26): 0 Open.** All 72 resolved — Fixed or a documented disposition
(Accepted-by-design B-C1/B-M1, Not-a-defect B-C2, Acknowledged/Mitigated for cosmetic/infra Lows).
⚠️ Runtime-verify on a live broker session before real-money trading: B-H3, B-H4, B-H5, B-H6.

---

## Backend — Trading Logic

| ID | Sev | Area | Defect | Status |
|----|-----|------|--------|--------|
| B-C1 | 🔴 | Risk/Reward | +2% target vs −40% stop = 1:20 R:R → needs 95.2% win-rate to break even (negative expectancy) | Accepted (2%/40% by design) |
| B-C2 | ⚪ | Order sizing | ~~Lot size 65 vs "75"~~ — NOT A DEFECT. 65 is the current NSE Nifty lot size (auditor used outdated reference). Tip: read it from the broker scrip master so it auto-updates on future NSE revisions. | Not a defect (65 is current) |
| B-C3 | 🔴 | Data integrity | Silently trades on SIMULATED data on auth/API failure (believes it's live) | Fixed (DataFeedStatus flag; RiskGuard blocks new trades on simulated feed in angelone mode — kill-switch `block-on-simulated-data`; live feed never blocked; exposed at `/api/v1/market/feed-status`) |
| B-C4 | 🔴 | Pricing | `fetchLtp` returns hardcoded 150.0 on failure → fake entry/SL/target/order price | Fixed (returns −1 sentinel; OrderExecutionService aborts the order instead of trading at a fake price) |
| B-C5 | 🔴 | Trade resolution | Flat 0.5 delta for all ladder strikes, ignores theta → mis-triggers SL/target, fake P&L | Fixed (live LTP first; fallback = Black-Scholes theoretical premium w/ real delta+theta; if neither, leave ACTIVE — never resolve on a fabricated price) |
| B-C6 | 🔴 | Risk | Strike ladder fires 3 orders, each 100% of wallet; no aggregate exposure cap | Fixed (per-order capital split across ladder legs via `splitAcross`; concurrent-position cap `max-concurrent-positions:6` in RiskGuard/DecisionAgent) |
| B-C7 | 🔴 | P&L | Live P&L ignores all costs (STT/brokerage/GST/stamp/slippage) → "winners" net losers | Fixed (new `OptionCostService` round-trip cost model; TradeResult.profitLoss now NET; gross/cost/net shown in Telegram; rates configurable under `nifty.costs.*`) |
| B-C8 | 🔴 | Risk | `RiskAgent` (R:R + VIX gate) is dead code — never called | Fixed (wired as advisory: logged + persisted as `Risk_RR` explanation + in Telegram alert) |
| B-H1 | 🟠 | Expiry/symbol | Expiry symbol uses server-TZ, rebuilt each cycle → wrong contract near rollover | Fixed (expiry calc uses `TimeUtil.todayIst()`/`nowIst()`) — persisting tradingsymbol on signal still a follow-up |
| B-H2 | 🟠 | Timezone | Server-TZ `LocalDateTime.now()` vs IST mismatch → wrong daily counts/limits, bad holdingTime | Fixed (new `TimeUtil` — signalTime, candle, data-client timestamps & expiry all IST, consistent with RiskGuard) |
| B-H3 | 🟠 | Concurrency | Scheduler overlap; `@Transactional collect()` wraps blocking HTTP → pool exhaustion, double orders | Fixed (AtomicBoolean non-reentrancy guard in scheduler; removed `@Transactional` from `collect()` so no DB connection is held across HTTP — runtime-verify on deploy) |
| B-H4 | 🟠 | Execution | Phantom positions: signal saved ACTIVE before order confirm; no orderId/reconciliation | Fixed (order placed FIRST; `executeOrder` returns PLACED/SKIPPED/FAILED; FAILED ⇒ no ACTIVE signal; `order_id` stored on TradeSignal + V1.5 migration — continuous broker-book reconciliation still a follow-up; runtime-verify when live) |
| B-H5 | 🟠 | Execution | Bracket LIMIT at stale/fake price; no tick-align, partial-fill, or gap handling | Partial (stale/fake price fixed via B-C4 abort-on-no-LTP; order-type/partial-fill/gap handling needs LIVE broker verification — do not change blind) |
| B-H6 | 🟠 | Pricing | IV skew ignored — IV hardcoded 12.5 for all strikes (CE & PE) → mispriced BS premiums | Fixed (`BlackScholesService.impliedVol` Newton-Raphson solver, unit-tested; live chain backs out per-strike IV from CE/PE LTP, falls back to 12.5 — non-regressive) |
| B-H7 | 🟠 | Signals | OI-change = 0 on first cycle after restart (in-memory baseline) → safety penalties skipped | Fixed (`seedOiBaselineIfNeeded` seeds lastCe/PeOi maps from the last stored snapshot on first live fetch) |
| B-M1 | 🟡 | Strategy | Target1 stored but never used (no partial exit) | Won't Fix (by design — partial-exit declined) |
| B-M2 | 🟡 | Risk | Risk limits effectively off: 100% capital/order, 50 trades/day, ₹10L loss cap | Fixed (safer defaults: capital/order 100→20%, daily-loss 10L→25k; still configurable) |
| B-M3 | 🟡 | Trade resolution | No gap / "both hit" / EOD-expiry square-off; `EXPIRED` outcome never produced | Fixed (EOD/expiry square-off: options past weekly expiry resolved as EXPIRED at residual value) |
| B-M4 | 🟡 | Trade resolution | Entry spot reconstructed (findLatestBefore) instead of stored → wrong anchor | Fixed (entry_spot stored on signal + V1.6 migration; reconstruction only for legacy rows) |
| B-M5 | 🟡 | Regime | Sideways gate inert (ATR factor 0.10 rarely flags; regime not fed to confidence) | Acknowledged (regime already feeds the Trend confidence factor + sideways-extra-gate; ATR factor tunable) |
| B-M6 | 🟡 | ML | Weight tuner learns from fabricated/phantom outcomes; LR 0.5 skews | Fixed (learning-rate 0.5→0.25; trains on real outcomes post-B-C5) |
| B-M7 | 🟡 | Alerts | Telegram says "Stop Loss (2%)" but SL is 40% | Fixed (labels now show actual `targetProfitPercent`/`stopLossPercent`) |
| B-M8 | 🟡 | Performance | `count()` each cycle + N+1 queries → cycle slows with data | Fixed (cache history-sufficient flag; aggregate sumProfitLossSince removes RiskGuard N+1) |
| B-L1 | 🟢 | Broker | Hardcoded fake client IP/MAC in broker requests | Acknowledged (placeholder IP/MAC accepted by broker; cosmetic) |
| B-L2 | 🟢 | Data | Scrip-master failure seeds only spot+VIX → silent "no trades" | Mitigated (logs ERROR; a missing scrip now aborts the order via B-H4 → no bad trades) |
| B-L3 | 🟢 | Auth | TOTP uses only current 30s window (no ±1) → skew → silent fallback | Acknowledged (clock-sync/infra; B-C3 now surfaces simulated state if auth fails) |
| B-L4 | 🟢 | Expiry | Weekly-expiry hardcoded Thursday in 2 places — verify current NSE rule | Acknowledged (uses Thursday; verify current NSE weekly-expiry rule before launch) |
| B-L5 | 🟢 | Auth | JWT never refreshed (~24h expiry) → mid-session failure → silent fallback | Fixed (8h JWT TTL → proactive re-auth before expiry) |
| B-L6 | 🟢 | Robustness | Broad `catch(Exception)` + unchecked Map casts hide cycle failures | Acknowledged (defensive; failures logged + surfaced via DataFeedStatus) |
| B-L7 | 🟢 | Observability | Failed cycle emits no health/alert (looks successful) | Acknowledged (cycle failure logged at ERROR; DataFeedStatus flags degraded feed) |
| B-L8 | 🟢 | Pricing | BS floors T at 1 day, no dividend; minor tail error | Acknowledged (T floored at 1 day, no dividend — acceptable approximation) |

## Frontend — Portal

| ID | Sev | Area | Defect | Status |
|----|-----|------|--------|--------|
| F-C1 | 🔴 | Crash | Dashboard crashes on missing market field (`.toFixed`/`.toLocaleString` on null) | Fixed (`mergeDefined` — only defined/non-null/non-NaN fields merged onto seeded state) |
| F-C2 | 🔴 | Crash | Dashboard tick handler overwrites good values with undefined | Fixed (tick uses `mergeDefined`) |
| F-C3 | 🔴 | Crash | OptionChain renders ceOi/peOi/strikePrice unguarded → NaN/crash | Fixed (`sanitizeChain` drops strike-less rows + coerces numerics; tick merge guarded) |
| F-C4 | 🔴 | Crash | OptionChain `spotPrice.toLocaleString` crashes if niftySpot missing | Fixed (spot setters only set when niftySpot present) |
| F-C5 | 🔴 | Crash | AiSignals renders entry/target/SL/confidence/date unguarded | Fixed (`sanitizeSignals` coerces numerics + requires id; `fmtSignalTime` safe date) |
| F-C6 | 🔴 | Data honesty | WS sets live=true on every frame → mock data shown as "Live" | Fixed (only apply+claim live on non-empty frames) |
| F-C7 | 🔴 | Data honesty | REST sets live=true while keeping mock (empty live array) | Fixed (`setLive(clean.length>0)`; chip relabeled "Demo data — not live") |
| F-C8 | 🔴 | Data honesty | Global "Live • Streaming" chip from /health only, not real frames | Fixed (new `useFeedStatus` → `/market/feed-status`; chip shows Offline/Simulated/Live honestly) |
| F-H1 | 🟠 | Risk display | StrategyBuilder unlimited-loss detection dead (`&& false`) → finite loss for naked shorts | Fixed (rewritten via asymptotic edge-slope on both sides; short straddle now shows Unlimited loss) |
| F-H2 | 🟠 | Math | Payoff window ±10% → wrong max P&L/breakevens for wide strategies | Fixed (domain now extends beyond outermost strikes + padding; needed for F-H1) |
| F-H3 | 🟠 | Forms | Lots input accepts empty/NaN/decimal/0 | Fixed (`setLots` coerces to positive integer) |
| F-H4 | 🟠 | Forms | Calculators: NaN propagates ("₹NaN"); negatives accepted | Fixed (`num()` helper on all inputs → non-negative finite) |
| F-H5 | 🟠 | Math | Calculators R:R wrong for inverted prices | Fixed (shows validation hint unless Stop < Entry < Target) |
| F-H6 | 🟠 | Math | Position-size division by zero (stopPoints=0 → "Infinity Lots") | Fixed (`stopPoints>0` guard → 0 lots) |
| F-H7 | 🟠 | Accuracy | Brokerage calc fabricates flat ₹1.5 "NSE tax"; STT/GST/stamp omitted | Fixed (relabeled "approx"; disclaimer that STT/stamp/SEBI excluded) |
| F-H8 | 🟠 | Rendering | LearningCenter shows raw LaTeX (no math plugin) | Fixed (mock PCR formula rewritten as plain text; add remark-math later if backend emits LaTeX) |
| F-H9 | 🟠 | Data honesty | LearningCenter keeps mock articles with no indicator | Fixed ("Sample content" chip when demo/empty) |
| F-H10 | 🟠 | Error state | NewsIntelligence "Generate" failure is silent | Fixed (`genMsg` success/error Alert) |
| F-H11 | 🟠 | Null-safety | NewsIntelligence crashes/mislabels on malformed item | Fixed (`sanitizeNews` requires id + defaults; `fmtNewsDate` safe) |
| F-M1 | 🟡 | WebSocket | STOMP client never deactivated (socket/reconnect leak) | Fixed (ref-counted teardown: deactivate 30s after last unsubscribe) |
| F-M2 | 🟡 | WebSocket | `useStreamConnected` initial-state edge cases | Acknowledged (minor; initial-state edge already handled) |
| F-M3 | 🟡 | Correctness | OptionChain ATM hardcodes /50 step → ATM chip can miss | Fixed (ATM = nearest actual chain strike to spot) |
| F-M4 | 🟡 | Data honesty | Dashboard/OptionChain flash mock numbers with no loading state | Fixed ("Connecting…" chip while live===null on Dashboard/OptionChain) |
| F-M5 | 🟡 | Forms | Performance: no max date-span; `winRatePercentage` → "undefined%" | Fixed (winRate `?? 0` guard; max-span still optional) |
| F-M6 | 🟡 | Labeling | Performance "Target Hits" ambiguous (target2 only) | Fixed (relabeled "Target-2 Hits") |
| F-M7 | 🟡 | Security/Render | AI markdown unsanitized layer; h1/h2 unstyled | Fixed (h1/h2 styled; react-markdown is safe-by-default, no raw HTML) |
| F-M8 | 🟡 | Labeling | AiSignals "EXPIRED" tab = any non-active (incl. target/SL) | Fixed (tab renamed "CLOSED") |
| F-M9 | 🟡 | State | Demo chip stuck on after recovery (no REST re-poll) | Mitigated (useFeedStatus polls /feed-status every 10s → global chip reconciles) |
| F-M10 | 🟡 | Accuracy | Dashboard pivots labeled "yesterday close" but are arbitrary bands off live spot | Fixed (copy now says indicative ±0.5%/±1.2% bands off live spot) |
| F-M11 | 🟡 | Correctness | Dashboard VIX/trend treat undefined as lowest bucket | Fixed (resolved by F-C1 `mergeDefined` — marketData always seeded) |
| F-L1 | 🟢 | Memory | AdSense setTimeout not cleared → state update after unmount | Fixed (clearTimeout in cleanup) |
| F-L2 | 🟢 | UX | AdSense fallback is fake "ad" linking to AdSense signup | Fixed (renders null in prod; dev-only neutral placeholder, no fake clickable ad) |
| F-L3 | 🟢 | Styling | OptionChain row hover white-on-white (invisible) | Fixed (hover → action.hover) |
| F-L4 | 🟢 | Responsive | Chart X-axis strike ticks collide on mobile | Fixed (XAxis interval=preserveStartEnd + smaller ticks) |
| F-L5 | 🟢 | A11y | Missing aria-labels; color-only bullish/bearish | Fixed (aria-label on remove-leg; menu button already labeled) |
| F-L6 | 🟢 | Formatting | Inconsistent min/max fraction digits | Acknowledged (minor decimal-place inconsistency) |
| F-L7 | 🟢 | Labeling | Option-profit calc "NET PNL" is actually gross | Fixed (relabeled "Gross P&L (before costs)") |
| F-L8 | 🟢 | UX | ErrorBoundary leaks raw error strings in prod | Fixed (raw error dev-only; friendly message in prod) |
| F-L9 | 🟢 | Minor | useBackendStatus requests not aborted on cleanup | Fixed (AbortController aborts in-flight on cleanup) |
| F-L10 | 🟢 | Edge case | StrategyBuilder empty-premiums → string strikes | Fixed (empty-premiums guard with message) |
| F-L11 | 🟢 | Forms | SIP calc accepts negative/absurd returns | Fixed (via F-H4 num() → inputs coerced non-negative) |

---

## Launch blockers (fix first)
~~`B-C1`~~ (kept by design) + `B-C8` ✅ (RiskAgent wired) · ~~`B-C2`~~ (not a defect — 65 is current) · `B-C3` + `B-C4` ✅ (fake-data guard) · `B-C5` (resolution) · `B-C6 B-M2` (exposure caps) · `B-C7` (costs) · `B-H3 B-H4` (txn/HTTP + phantom orders) · `F-C1…F-C5` (crash guards) · `F-C6…F-C8` (live/demo honesty) · `F-H1` (unlimited loss)
