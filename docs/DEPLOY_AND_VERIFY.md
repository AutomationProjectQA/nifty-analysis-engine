# Deploy & Verification Checklist — `fix/production-data-chain`

> Turnkey steps to deploy the branch and confirm, page by page, that data is REAL (not simulated/mock).
> Replace `<DOMAIN>` with your site. Best run **during market hours (09:15–15:30 IST)** — off-hours the feed is legitimately frozen at the last close.

---

## 1. Pre-deploy
- [ ] On branch `fix/production-data-chain`, build is green: `mvn -q test` (151 tests) and `cd frontend && npx vite build`.
- [ ] **Env on the VM** (these silently degrade if missing — the new `StartupHealthLogger` will WARN about them at boot):
  - [ ] `GEMINI_API_KEY` set (else News/Reports fall back to templates).
  - [ ] Angel One creds valid / session not expired (else feed is simulated).
- [ ] Flyway migrations present and will run: **V1.9** (option LTP), **V1.10** (learning content), **V1.11** (snapshot OHLC/52-wk), **V1.12** (decision_trace), **V1.13** (unique constraints).
  - ⚠️ V1.13 dedupes before adding UNIQUE constraints — it deletes duplicate rows (keeps newest). Expected on existing data.

## 2. Deploy
- [ ] Deploy backend (systemd service) — see `vm-deployment-setup` notes.
- [ ] Deploy frontend (`frontend/dist` from a **production** build → `VITE_API_BASE_URL` empty = same origin via nginx).
- [ ] Backend started cleanly — check logs for the `=== Startup health ===` block; no migration failures.

## 3. Connection + feed sanity (30 seconds)
- [ ] `https://<DOMAIN>/api/v1/market/feed-status` → returns JSON (proves frontend-origin → backend reachable).
- [ ] **`https://<DOMAIN>/api/v1/market/latest`** → now returns the **NIFTY** row (not BANKNIFTY). Check `niftySpot`:
  - **~24,0xx (real Nifty)** → feed is genuinely LIVE ✅
  - **~23,500** → NIFTY is on the SIMULATED fallback → fix Angel One session/credentials; the UI will show the orange "Simulated feed" chip.
- [ ] In the browser: DevTools → Network → filter `api` → all `/api/v1/...` calls are **200** (not failing).

## 4. Per-page verification
- [ ] **Dashboard** — spot/future/VIX match real Nifty; chip shows **● Live** (green), not "Simulated"/"Demo". Key-stats card shows real prev-close / day high-low / 52-wk (52-wk may be "—" if the index quote is OHLC-mode only).
- [ ] **Live Nifty Chart** — candlesticks render (NOT Apple); updates through the session. (Needs collected `market_candle` rows — fills in as the feed runs.)
- [ ] **Option Chain** — real strikes around spot, **LTP column populated**, OI/IV present; "● Live" chip. No 23,300–23,700 mock band.
- [ ] **Strategy Builder** — premiums non-zero for ATM/near strikes (prefers real LTP); spot in the header is real.
- [ ] **News Intelligence** — real headlines with "Read full story" links (not the "+1,250 Cr FII" boilerplate).
- [ ] **Market Reports** — multi-paragraph pre/post-market report (not a one-liner).
- [ ] **Learning Center** — open an article → modal **scrolls**; content is long-form.
- [ ] **AI Signals** — see §5 (depends on market movement + gates).

### The "static/mock tells" (if you see these EXACT values, that page got no real data)
- Spot **23,510.50**, future **23,548.20**, VIX **13.40** (Dashboard mock)
- Option Chain spot **23,510.50**, strikes 23,300–23,700
- News "**+1,250 Cr** FII / Dow **+180 pts**"

## 5. Trade generation — observe, don't guess (Phase-0/4 endpoints)
- [ ] **`/api/v1/signals/decision-funnel`** — today's evaluations by outcome/reject stage. This is the baseline: see *where* candidates die (e.g. `direction_consensus`, `min_confirmation`, `per_strike_filters`, `confidence_gate`) and how many `EMITTED`. Look for `ml_rescue` notes (the default-on ML rescue).
- [ ] **`/api/v1/signals/decision-traces`** — last 100 evaluations in detail (per-gate notes).
- [ ] **`/api/v1/signals/calibration`** — once trades resolve: does "predicted 80%" actually win ~80%?
- [ ] **`/api/v1/signals/factor-effectiveness`** — which factors separate winners from losers (prune the noisy ones).
- [ ] **`/api/v1/signals/drift`** — recent vs historical win-rate; `degraded` flag.
- [ ] **`/api/v1/signals/replay-compare?start=...&end=...&candidateGate=55`** — safe A/B of a candidate gating threshold over history (non-persisting).

**Tune from the funnel, not by guessing.** If a gate is killing too many, adjust its config knob (below) and re-watch.

## 6. Config toggles (no code redeploy — just config)
- `nifty.signal.min-direction-agreement` (2) — raise to 3 once you have quality data.
- `nifty.signal.ml-rescue-enabled` (true) / `ml-rescue-min-probability` (0.75) — **the one gate-flow change that affects trade gen**; turn off or raise the bar if rescued trades underperform (check `/calibration`).
- `nifty.confidence.continuous-scoring` (true) — A/B vs old buckets.
- `nifty.calibration.max-required-winrate` (0.65) — caps the calibration bar.
- `nifty.instruments.banknifty-enabled` (false) — leave OFF until BANKNIFTY's live quote path is built.

## 7. Owner decisions still pending (not code)
- **2% target / 20% stop reward:risk** — root of the calibration difficulty (break-even ~91%). Decide before relying on the calibrated gate.
- Real **FII/DII** + real **GIFT Nifty** data sources (currently omitted / estimated).
- Confirm the **WebSocket binary tick offsets** are correct during the first live session — watch logs for `"dropping implausible … tick"` warnings (the safety guard).

## 8. What "healthy" looks like
- feed-status `LIVE` **and** `/market/latest` NIFTY spot ≈ real Nifty.
- Dashboard "● Live" chip; chart drawing; Option Chain LTPs; non-zero Strategy premiums; real news/reports.
- `/decision-funnel` shows evaluations flowing and a non-zero `EMITTED` (or clearly identifies the blocking gate to tune).
- No `"Async decision evaluation failed"` or repeated `"dropping implausible tick"` in logs.

## 9. Rollback
- Backend issue → redeploy previous artifact (systemd) — DB migrations are additive/safe.
- Specific behavior regression → flip the relevant config toggle in §6 (no redeploy) before rolling back.
