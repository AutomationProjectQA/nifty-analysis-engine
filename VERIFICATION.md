# Verification Runbook

How to confirm the engine works end-to-end against the goal:
**generate Nifty option trades with a 2% profit target, risk-controlled, with real INR P&L.**

Three layers: automated → runtime smoke → UI end-to-end.

---

## Layer 1 — Automated (proves the logic)
```bash
mvn test                 # 53/53 — gate, blend, cold-start, risk guard, real-LTP, backtest, provenance, ONNX schema, feature parity
cd frontend && npm run build   # clean build — all pages compile
```
What it proves: the decision gate fires correctly, the risk guard blocks correctly, trades use the 2% target / quantity, the backtest models costs, every signal records provenance, and a mismatched ONNX model is rejected.

---

## Layer 2 — Runtime smoke (the app actually running, NO broker needed)

**1. Start infra**
```bash
docker-compose up -d          # postgres:16 + redis:7
```

**2. Configure for SAFE verification** (edit `src/main/resources/application.yml`, or use env):
```yaml
nifty:
  collector:
    provider: "simulated"      # fake data — no Angel One creds / no real orders
    market-hours-only: false   # run 24/7
  gating-threshold: 45.0       # TEMP: lower so a trade fires quickly (restore to 60 after)
  order-execution:
    enabled: false             # simulation only — never place a live order while testing
  risk:
    trading-enabled: true
```

**3. Start the backend**
```bash
./run-local.sh                 # (loads .env) or: mvn spring-boot:run
```

**4. Watch the logs for the full pipeline** (these lines = success):
- `ONNX model loaded and schema-validated`
- `Starting market and option chain data collection cycle`
- `Market Snapshot persisted: Spot=…`
- `Decision Agent executing trade signals search`
- `Confidence blend -> ONNX=…% , Agent=…% => Raw=…%`  *(or "ONNX model not ready … Using rule-based agent confidence")*
- **`Saved trade signal (id=…)`  ← THE GOAL: a trade generated** (the original "no trades" bug is fixed)

If you instead see `Signal confidence (…%) below threshold. NO TRADE` every cycle, lower `gating-threshold` further — the grep tells you which gate blocked.

**5. Hit every endpoint** (after ~2–3 collection cycles):
```bash
curl localhost:8080/api/v1/health                       # {"status":"UP", components: db/redis/model/tradingEnabled}
curl localhost:8080/api/v1/market/latest                # spot/future/vix/ema/rsi/vwap
curl localhost:8080/api/v1/options/latest               # option chain
curl localhost:8080/api/v1/signals                      # generated signals (non-empty)
curl localhost:8080/api/v1/analytics/summary            # totalTrades, winRatePercentage, totalProfitLossInr
curl -X POST "localhost:8080/api/v1/analytics/backtest/run?start=2026-06-01T00:00:00&end=2026-12-31T23:59:59"
curl -X POST "localhost:8080/api/v1/reports/generate?type=PRE_MARKET"   # Gemini (or template) report
curl -X POST  localhost:8080/api/v1/news/generate                        # news summary
# validation works -> should return HTTP 400:
curl -i "localhost:8080/api/v1/reports/history?type=PRE_MARKET&page=-1"
```

**6. Confirm a signal embodies the goal** — pick one from `/api/v1/signals` and check:
- `target2 ≈ entry × 1.02` (the **2% profit target**)
- `stopLoss ≈ entry × 0.60` (the configured stop)
- `quantity` is set (lot-aligned) → enables **real INR P&L**
- `confidence ≥ gating-threshold`

**7. Confirm risk controls** (each = one restart):
- `max-trades-per-day: 1` → after 1 trade: `Risk guard blocked new trade: Max trades per day reached (1/1)`
- `trading-enabled: false` → `Risk guard blocked new trade: Trading is disabled (kill switch…)`

---

## Layer 3 — UI end-to-end
```bash
cd frontend && npm run dev      # open the printed localhost URL
```
- **Header chip** = green **"Live Connection"** with backend up; stop the backend → **"Offline — demo data"** within ~15s.
- **Dashboard** — real spot, spot-vs-VWAP, VIX regime label (no fabricated change %).
- **AI Signals** — shows generated signals, or a real "No signals yet" empty state (no fake mock).
- **Performance** — summary cards; run a backtest → **net/gross P&L, costs, win-rate** in INR.
- **Reports / News** — click **"Generate now"** → content appears (empty-state before).
- Navigate every page — none crash (error boundary catches any that would).

---

## Layer 4 — Restore production-safe config (after verifying)
- `gating-threshold` → `60.0`
- `provider` → `angelone` only when going live (with valid creds)
- `order-execution.enabled` → `true` only when you intend to place real orders
- **Rotate the leaked credentials** before any live use (they're in git history)

---

## What this does NOT verify
- **Profitability / model edge** — run `python src/main/python/train_model.py --version 2` and read `MODEL_CARD.md`: the walk-forward accuracy must beat the majority-class baseline. A green pipeline ≠ a profitable strategy.
- **Live order fills** — `order-execution.enabled: true` against a real Angel One session is untested here; verify with a single small manual trade first.
