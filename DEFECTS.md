# Defect List — Nifty Analysis Engine

Trackable defect log. Detailed write-ups + fixes in [PRE_LAUNCH_AUDIT.md](PRE_LAUNCH_AUDIT.md).

**Severity:** 🔴 Critical (blocks launch) · 🟠 High · 🟡 Medium · 🟢 Low
**Status:** Open / In&nbsp;Progress / Fixed / Won't&nbsp;Fix
**Totals:** 35 defects open (16 🔴 · 18 🟠 → shown; see audit for full 72)

---

## Backend — Trading Logic

| ID | Sev | Area | Defect | Status |
|----|-----|------|--------|--------|
| B-C1 | 🔴 | Risk/Reward | +2% target vs −40% stop = 1:20 R:R → needs 95.2% win-rate to break even (negative expectancy) | Accepted (2%/40% by design) |
| B-C2 | ⚪ | Order sizing | ~~Lot size 65 vs "75"~~ — NOT A DEFECT. 65 is the current NSE Nifty lot size (auditor used outdated reference). Tip: read it from the broker scrip master so it auto-updates on future NSE revisions. | Not a defect (65 is current) |
| B-C3 | 🔴 | Data integrity | Silently trades on SIMULATED data on auth/API failure (believes it's live) | Fixed (DataFeedStatus flag; RiskGuard blocks new trades on simulated feed in angelone mode — kill-switch `block-on-simulated-data`; live feed never blocked; exposed at `/api/v1/market/feed-status`) |
| B-C4 | 🔴 | Pricing | `fetchLtp` returns hardcoded 150.0 on failure → fake entry/SL/target/order price | Fixed (returns −1 sentinel; OrderExecutionService aborts the order instead of trading at a fake price) |
| B-C5 | 🔴 | Trade resolution | Flat 0.5 delta for all ladder strikes, ignores theta → mis-triggers SL/target, fake P&L | Fixed (live LTP first; fallback = Black-Scholes theoretical premium w/ real delta+theta; if neither, leave ACTIVE — never resolve on a fabricated price) |
| B-C6 | 🔴 | Risk | Strike ladder fires 3 orders, each 100% of wallet; no aggregate exposure cap | Open |
| B-C7 | 🔴 | P&L | Live P&L ignores all costs (STT/brokerage/GST/stamp/slippage) → "winners" net losers | Open |
| B-C8 | 🔴 | Risk | `RiskAgent` (R:R + VIX gate) is dead code — never called | Fixed (wired as advisory: logged + persisted as `Risk_RR` explanation + in Telegram alert) |
| B-H1 | 🟠 | Expiry/symbol | Expiry symbol uses server-TZ, rebuilt each cycle → wrong contract near rollover | Open |
| B-H2 | 🟠 | Timezone | Server-TZ `LocalDateTime.now()` vs IST mismatch → wrong daily counts/limits, bad holdingTime | Open |
| B-H3 | 🟠 | Concurrency | Scheduler overlap; `@Transactional collect()` wraps blocking HTTP → pool exhaustion, double orders | Open |
| B-H4 | 🟠 | Execution | Phantom positions: signal saved ACTIVE before order confirm; no orderId/reconciliation | Open |
| B-H5 | 🟠 | Execution | Bracket LIMIT at stale/fake price; no tick-align, partial-fill, or gap handling | Open |
| B-H6 | 🟠 | Pricing | IV skew ignored — IV hardcoded 12.5 for all strikes (CE & PE) → mispriced BS premiums | Open |
| B-H7 | 🟠 | Signals | OI-change = 0 on first cycle after restart (in-memory baseline) → safety penalties skipped | Open |
| B-M1 | 🟡 | Strategy | Target1 stored but never used (no partial exit) | Open |
| B-M2 | 🟡 | Risk | Risk limits effectively off: 100% capital/order, 50 trades/day, ₹10L loss cap | Open |
| B-M3 | 🟡 | Trade resolution | No gap / "both hit" / EOD-expiry square-off; `EXPIRED` outcome never produced | Open |
| B-M4 | 🟡 | Trade resolution | Entry spot reconstructed (findLatestBefore) instead of stored → wrong anchor | Open |
| B-M5 | 🟡 | Regime | Sideways gate inert (ATR factor 0.10 rarely flags; regime not fed to confidence) | Open |
| B-M6 | 🟡 | ML | Weight tuner learns from fabricated/phantom outcomes; LR 0.5 skews | Open |
| B-M7 | 🟡 | Alerts | Telegram says "Stop Loss (2%)" but SL is 40% | Open |
| B-M8 | 🟡 | Performance | `count()` each cycle + N+1 queries → cycle slows with data | Open |
| B-L1 | 🟢 | Broker | Hardcoded fake client IP/MAC in broker requests | Open |
| B-L2 | 🟢 | Data | Scrip-master failure seeds only spot+VIX → silent "no trades" | Open |
| B-L3 | 🟢 | Auth | TOTP uses only current 30s window (no ±1) → skew → silent fallback | Open |
| B-L4 | 🟢 | Expiry | Weekly-expiry hardcoded Thursday in 2 places — verify current NSE rule | Open |
| B-L5 | 🟢 | Auth | JWT never refreshed (~24h expiry) → mid-session failure → silent fallback | Open |
| B-L6 | 🟢 | Robustness | Broad `catch(Exception)` + unchecked Map casts hide cycle failures | Open |
| B-L7 | 🟢 | Observability | Failed cycle emits no health/alert (looks successful) | Open |
| B-L8 | 🟢 | Pricing | BS floors T at 1 day, no dividend; minor tail error | Open |

## Frontend — Portal

| ID | Sev | Area | Defect | Status |
|----|-----|------|--------|--------|
| F-C1 | 🔴 | Crash | Dashboard crashes on missing market field (`.toFixed`/`.toLocaleString` on null) | Open |
| F-C2 | 🔴 | Crash | Dashboard tick handler overwrites good values with undefined | Open |
| F-C3 | 🔴 | Crash | OptionChain renders ceOi/peOi/strikePrice unguarded → NaN/crash | Open |
| F-C4 | 🔴 | Crash | OptionChain `spotPrice.toLocaleString` crashes if niftySpot missing | Open |
| F-C5 | 🔴 | Crash | AiSignals renders entry/target/SL/confidence/date unguarded | Open |
| F-C6 | 🔴 | Data honesty | WS sets live=true on every frame → mock data shown as "Live" | Open |
| F-C7 | 🔴 | Data honesty | REST sets live=true while keeping mock (empty live array) | Open |
| F-C8 | 🔴 | Data honesty | Global "Live • Streaming" chip from /health only, not real frames | Open |
| F-H1 | 🟠 | Risk display | StrategyBuilder unlimited-loss detection dead (`&& false`) → finite loss for naked shorts | Open |
| F-H2 | 🟠 | Math | Payoff window ±10% → wrong max P&L/breakevens for wide strategies | Open |
| F-H3 | 🟠 | Forms | Lots input accepts empty/NaN/decimal/0 | Open |
| F-H4 | 🟠 | Forms | Calculators: NaN propagates ("₹NaN"); negatives accepted | Open |
| F-H5 | 🟠 | Math | Calculators R:R wrong for inverted prices | Open |
| F-H6 | 🟠 | Math | Position-size division by zero (stopPoints=0 → "Infinity Lots") | Open |
| F-H7 | 🟠 | Accuracy | Brokerage calc fabricates flat ₹1.5 "NSE tax"; STT/GST/stamp omitted | Open |
| F-H8 | 🟠 | Rendering | LearningCenter shows raw LaTeX (no math plugin) | Open |
| F-H9 | 🟠 | Data honesty | LearningCenter keeps mock articles with no indicator | Open |
| F-H10 | 🟠 | Error state | NewsIntelligence "Generate" failure is silent | Open |
| F-H11 | 🟠 | Null-safety | NewsIntelligence crashes/mislabels on malformed item | Open |
| F-M1 | 🟡 | WebSocket | STOMP client never deactivated (socket/reconnect leak) | Open |
| F-M2 | 🟡 | WebSocket | `useStreamConnected` initial-state edge cases | Open |
| F-M3 | 🟡 | Correctness | OptionChain ATM hardcodes /50 step → ATM chip can miss | Open |
| F-M4 | 🟡 | Data honesty | Dashboard/OptionChain flash mock numbers with no loading state | Open |
| F-M5 | 🟡 | Forms | Performance: no max date-span; `winRatePercentage` → "undefined%" | Open |
| F-M6 | 🟡 | Labeling | Performance "Target Hits" ambiguous (target2 only) | Open |
| F-M7 | 🟡 | Security/Render | AI markdown unsanitized layer; h1/h2 unstyled | Open |
| F-M8 | 🟡 | Labeling | AiSignals "EXPIRED" tab = any non-active (incl. target/SL) | Open |
| F-M9 | 🟡 | State | Demo chip stuck on after recovery (no REST re-poll) | Open |
| F-M10 | 🟡 | Accuracy | Dashboard pivots labeled "yesterday close" but are arbitrary bands off live spot | Open |
| F-M11 | 🟡 | Correctness | Dashboard VIX/trend treat undefined as lowest bucket | Open |
| F-L1 | 🟢 | Memory | AdSense setTimeout not cleared → state update after unmount | Open |
| F-L2 | 🟢 | UX | AdSense fallback is fake "ad" linking to AdSense signup | Open |
| F-L3 | 🟢 | Styling | OptionChain row hover white-on-white (invisible) | Open |
| F-L4 | 🟢 | Responsive | Chart X-axis strike ticks collide on mobile | Open |
| F-L5 | 🟢 | A11y | Missing aria-labels; color-only bullish/bearish | Open |
| F-L6 | 🟢 | Formatting | Inconsistent min/max fraction digits | Open |
| F-L7 | 🟢 | Labeling | Option-profit calc "NET PNL" is actually gross | Open |
| F-L8 | 🟢 | UX | ErrorBoundary leaks raw error strings in prod | Open |
| F-L9 | 🟢 | Minor | useBackendStatus requests not aborted on cleanup | Open |
| F-L10 | 🟢 | Edge case | StrategyBuilder empty-premiums → string strikes | Open |
| F-L11 | 🟢 | Forms | SIP calc accepts negative/absurd returns | Open |

---

## Launch blockers (fix first)
~~`B-C1`~~ (kept by design) + `B-C8` ✅ (RiskAgent wired) · ~~`B-C2`~~ (not a defect — 65 is current) · `B-C3` + `B-C4` ✅ (fake-data guard) · `B-C5` (resolution) · `B-C6 B-M2` (exposure caps) · `B-C7` (costs) · `B-H3 B-H4` (txn/HTTP + phantom orders) · `F-C1…F-C5` (crash guards) · `F-C6…F-C8` (live/demo honesty) · `F-H1` (unlimited loss)
