# Canonical Feature Spec — Nifty ONNX Model

The model consumes **exactly these 8 features, in this order**. Both the training
pipeline ([train_model.py](../src/main/python/train_model.py)) and the serving path
([TechnicalIndicatorService.calculateHourlyFeatures](../src/main/java/com/nifty/analysis/service/TechnicalIndicatorService.java))
must produce identical values for the same input, or predictions are meaningless.

All features are computed on **hourly** Nifty candles (the live app groups 1-minute
snapshots into hourly candles to match the yfinance `1h` training data).

| # | Feature | Definition | Typical range | Insufficient-data fallback |
|---|---------|-----------|---------------|----------------------------|
| 0 | `RSI` | 14-period RSI on hourly close. Gains/losses averaged with a **simple rolling mean** (NOT Wilder's). | 0–100 | 50.0 (< 15 candles) |
| 1 | `Spot_to_EMA20` | `close / EMA20`, EMA span=20, `adjust=False` | ~1.0 | 1.0 |
| 2 | `EMA20_to_EMA50` | `EMA20 / EMA50`, spans 20/50, `adjust=False` | ~1.0 | 1.0 |
| 3 | `VIX` | India VIX (daily close, forward-filled to the hour) | ~10–25 | 15.0 |
| 4 | `Prev_Daily_Return` | `(prevClose − prevOpen) / prevOpen` | ~−0.03…0.03 | 0.0 |
| 5 | `BB_Width` | `(2 × 20-period rolling std) / (20-period rolling mean)` | ~0.005–0.05 | 0.015 |
| 6 | `MACD_Hist` | `MACD(12,26) − Signal(9)`, all EMAs `adjust=False` | points | 0.0 |
| 7 | `Volume_Ratio` | `volume / 20-period rolling mean volume` | ~0.5–3 | 1.0 |

## Label
`Target = 1` if the **next** hourly close > current hourly close, else `0`. The model
outputs `P(Target=1)` = bullish probability.

## ONNX I/O contract (asserted at load by `OnnxModelService.validateSchema`)
- **Input:** single tensor named `input`, shape `[None, 8]`, `float32`.
- **Output:** classifier with `zipmap:false` → `output[1]` (`probabilities`) is `[batch, 2]`;
  column 1 is the bullish probability. (A model that fails this contract is rejected at
  load and the engine falls back to the rule-based confidence.)

## Known parity risks (must stay aligned)
1. **RSI smoothing** — training uses a simple rolling mean. The live *snapshot* path
   (`calculateRsi`) uses Wilder's smoothing, but that value is **not** fed to the model;
   the model only sees `calculateHourlyFeatures` → `calculateSimpleRsi` (simple mean). Keep it that way.
2. **EMA seeding** — pandas `ewm(adjust=False)` seeds with the first value; the Java loop
   seeds `ema = candles[0].close`. Equivalent for `adjust=False` — do not change one without the other.
3. **Volume scale** — yfinance hourly volume vs the app's reconstructed candle volume differ
   in absolute scale; `Volume_Ratio` is self-normalizing, but verify it stays comparable.

Any change to a feature definition here **requires retraining** and a bump of the model version.
