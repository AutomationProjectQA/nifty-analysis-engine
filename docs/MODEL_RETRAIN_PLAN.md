# Model Retrain Plan — Standardize Indicators & Retrain ONNX

## Why
The ML model ([train_model.py](../src/main/python/train_model.py)) trains on **hourly** features, and the Java serving path ([TechnicalIndicatorService.calculateHourlyFeatures](../src/main/java/com/nifty/analysis/service/TechnicalIndicatorService.java)) mirrors that — so the *model* side is consistent. The gap is that the **direction/bias** logic ([TechnicalAgent.analyze](../src/main/java/com/nifty/analysis/agent/TechnicalAgent.java)) uses **per-minute snapshot** EMA/RSI/VWAP, a different indicator definition than the model consumes. We want one canonical indicator spec shared across direction, the model, and the confidence engine, then a model retrained against it with proper validation.

## Current feature set (8, hourly)
`RSI(14)`, `Spot/EMA20`, `EMA20/EMA50`, `VIX`, `PrevDailyReturn`, `BB_Width`, `MACD_Hist`, `Volume_Ratio`
Model: `GradientBoostingClassifier` → ONNX (`zipmap:false`, input name `input`, output[1] = `[batch,2]` probabilities). Label: next hour close > current.

## Known inconsistencies to fix first
1. **RSI**: Python uses a simple rolling mean; Java `calculateSimpleRsi` also uses a simple mean, but the live `calculateRsi` (snapshot path) uses Wilder's smoothing. Pick **one** (recommend Wilder's) and use it everywhere.
2. **EMA seeding**: Java seeds EMA with the first candle close; pandas `ewm(adjust=False)` seeds differently. Align the seeding so serving == training.
3. **Volume**: yfinance hourly volume vs. the app's reconstructed candle volume differ in scale — `Volume_Ratio` may not be comparable. Verify or drop.
4. **Direction vs model**: decide whether `TechnicalAgent.analyze` should use the same hourly indicators (consistency) or stay intraday (timing). If unified, re-validate live behavior.

## Plan
1. **Write a canonical feature spec** (one doc/JSON) defining each of the 8 features precisely (window, smoothing, seeding, units).
2. **Single source of truth**: implement the spec once in Java; have the Python script compute features identically (or, better, export features from the app's own historical data rather than yfinance, so training data == serving data).
3. **Feature-parity test**: a JUnit/PyTest pair that computes all 8 features on the same fixture rows and asserts Java == Python within tolerance. This is the guardrail that prevents drift.
4. **Retrain with walk-forward validation** (not a single chronological split): rolling train/test windows; report out-of-sample AUC/accuracy per window. Reject if no edge over a coin-flip baseline.
5. **Version the artifact**: name `nifty_model_vN.onnx`, record the training data range, feature spec hash, and validation metrics in a `MODEL_CARD.md`. Assert the input/output schema at load in `OnnxModelService`.
6. **Shadow-compare** the new model against the current one on recent data before switching the served model.

## Hygiene (independent of retrain)
- `train_model.py` pip-installs at runtime — move deps to `requirements.txt` and a pinned venv; don't auto-install in the script.
- The committed `.venv/` must not be in the repo (now gitignored).
- Commit the training data snapshot (or a reproducible fetch script with a fixed date range) so runs are reproducible.

## Effort
~2–3 focused sessions: (1) canonical spec + parity test, (2) retrain + walk-forward harness, (3) versioning + shadow compare. Touches the Python pipeline and adds a feature-parity test on the Java side.
