"""
Nifty hourly direction model — training pipeline.

Install deps first (no runtime pip-install):  pip install -r requirements.txt

Features (canonical order — see docs/FEATURE_SPEC.md):
  RSI, Spot/EMA20, EMA20/EMA50, VIX, Prev_Daily_Return, BB_Width, MACD_Hist, Volume_Ratio
Label: 1 if next hourly close > current close, else 0.

Validation is WALK-FORWARD (expanding window) — a single chronological split overstates
performance on time series. The model is only worth promoting if its mean out-of-sample
accuracy beats the majority-class baseline by a meaningful margin.

Usage:
  python train_model.py --version 2                 # train + export nifty_model_v2.onnx
  python train_model.py --version 2 --promote       # also overwrite the served nifty_model.onnx
"""
import argparse
import os

import numpy as np
import pandas as pd
import yfinance as yf
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.metrics import accuracy_score, roc_auc_score, classification_report
from sklearn.model_selection import TimeSeriesSplit
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

FEATURES = [
    "RSI", "Spot_to_EMA20_Ratio", "EMA20_to_EMA50_Ratio", "VIX",
    "Prev_Daily_Return", "BB_Width", "MACD_Hist", "Volume_Ratio",
]
# Minimum edge over the majority-class baseline to consider the model worth promoting.
MIN_EDGE = 0.02


def build_dataset():
    print("Step 1: Downloading 2 years of Nifty 1h data + daily India VIX...")
    nifty = yf.download("^NSEI", period="730d", interval="1h")
    vix = yf.download("^INDIAVIX", period="730d", interval="1d")
    nifty_daily = yf.download("^NSEI", period="730d", interval="1d")

    for df in (nifty, vix, nifty_daily):
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = df.columns.get_level_values(0)

    nifty_daily["Daily_Return"] = (nifty_daily["Close"] - nifty_daily["Open"]) / nifty_daily["Open"]
    nifty_daily["Prev_Daily_Return"] = nifty_daily["Daily_Return"].shift(1)

    daily_mapped = nifty_daily[["Prev_Daily_Return"]].copy()
    daily_mapped.index = daily_mapped.index.date
    vix_mapped = vix[["Close"]].rename(columns={"Close": "VIX"}).copy()
    vix_mapped.index = vix_mapped.index.date

    nifty["Date"] = nifty.index.date
    nifty = nifty.join(daily_mapped, on="Date").join(vix_mapped, on="Date")
    nifty["VIX"] = nifty["VIX"].ffill()
    nifty["Prev_Daily_Return"] = nifty["Prev_Daily_Return"].ffill()

    print("Step 2: Computing the 8 canonical features (see docs/FEATURE_SPEC.md)...")
    delta = nifty["Close"].diff()
    gain = (delta.where(delta > 0, 0)).rolling(window=14).mean()
    loss = (-delta.where(delta < 0, 0)).rolling(window=14).mean()
    rs = gain / (loss + 1e-9)
    nifty["RSI"] = 100 - (100 / (1 + rs))

    nifty["EMA20"] = nifty["Close"].ewm(span=20, adjust=False).mean()
    nifty["EMA50"] = nifty["Close"].ewm(span=50, adjust=False).mean()
    nifty["Spot_to_EMA20_Ratio"] = nifty["Close"] / nifty["EMA20"]
    nifty["EMA20_to_EMA50_Ratio"] = nifty["EMA20"] / nifty["EMA50"]

    nifty["BB_Mid"] = nifty["Close"].rolling(window=20).mean()
    nifty["BB_Std"] = nifty["Close"].rolling(window=20).std()
    nifty["BB_Width"] = (2.0 * nifty["BB_Std"]) / (nifty["BB_Mid"] + 1e-9)

    ema12 = nifty["Close"].ewm(span=12, adjust=False).mean()
    ema26 = nifty["Close"].ewm(span=26, adjust=False).mean()
    macd = ema12 - ema26
    nifty["MACD_Hist"] = macd - macd.ewm(span=9, adjust=False).mean()

    nifty["Volume_Ratio"] = nifty["Volume"] / (nifty["Volume"].rolling(window=20).mean() + 1e-9)

    nifty["Target"] = (nifty["Close"].shift(-1) > nifty["Close"]).astype(int)
    clean = nifty.dropna()
    X = clean[FEATURES].astype(np.float32).values
    y = clean["Target"].values
    print(f"Dataset: {X.shape[0]} rows, {X.shape[1]} features")
    return X, y


def walk_forward_eval(X, y, n_splits=5):
    """Expanding-window walk-forward validation; returns (mean_acc, mean_auc, baseline)."""
    print(f"\nStep 3: Walk-forward validation ({n_splits} expanding folds)...")
    tscv = TimeSeriesSplit(n_splits=n_splits)
    accs, aucs = [], []
    for i, (tr, te) in enumerate(tscv.split(X), 1):
        model = GradientBoostingClassifier(random_state=42)
        model.fit(X[tr], y[tr])
        proba = model.predict_proba(X[te])[:, 1]
        pred = (proba >= 0.5).astype(int)
        acc = accuracy_score(y[te], pred)
        auc = roc_auc_score(y[te], proba) if len(set(y[te])) > 1 else float("nan")
        accs.append(acc)
        aucs.append(auc)
        print(f"  Fold {i}: OOS accuracy={acc:.4f}, AUC={auc:.4f}")

    mean_acc, mean_auc = float(np.mean(accs)), float(np.nanmean(aucs))
    # Majority-class baseline on the full label distribution.
    baseline = max(np.mean(y), 1 - np.mean(y))
    print(f"\n  Mean OOS accuracy: {mean_acc:.4f} | Mean AUC: {mean_auc:.4f}")
    print(f"  Majority-class baseline: {baseline:.4f} | Edge: {mean_acc - baseline:+.4f}")
    if mean_acc < baseline + MIN_EDGE:
        print(f"  WARNING: model does NOT beat baseline by >= {MIN_EDGE:.2f}. Do not promote — it has no real edge.")
    else:
        print("  OK: model shows an edge over the baseline.")
    return mean_acc, mean_auc, baseline


def export(model, version, out_dir, mean_acc, mean_auc, baseline, n_rows, promote):
    print("\nStep 5: Exporting ONNX + model card...")
    initial_type = [("input", FloatTensorType([None, len(FEATURES)]))]
    onnx_model = convert_sklearn(model, initial_types=initial_type,
                                 options={id(model): {"zipmap": False}})

    os.makedirs(out_dir, exist_ok=True)
    versioned = os.path.join(out_dir, f"nifty_model_v{version}.onnx")
    with open(versioned, "wb") as f:
        f.write(onnx_model.SerializeToString())
    print(f"  Wrote {versioned}")

    card = os.path.join(out_dir, "MODEL_CARD.md")
    with open(card, "w") as f:
        f.write(f"""# Model Card — nifty_model_v{version}

- **Algorithm:** GradientBoostingClassifier (sklearn) -> ONNX (zipmap:false)
- **Features (order):** {', '.join(FEATURES)}
- **Label:** next hourly close > current close
- **Training rows:** {n_rows}
- **Walk-forward mean OOS accuracy:** {mean_acc:.4f}
- **Walk-forward mean AUC:** {mean_auc:.4f}
- **Majority-class baseline:** {baseline:.4f} (edge: {mean_acc - baseline:+.4f})
- **ONNX I/O:** input `input` [None, {len(FEATURES)}] float32; output[1] = probabilities [batch, 2]

See docs/FEATURE_SPEC.md for exact feature definitions. Shadow-compare against the
currently-served model before promoting.
""")
    print(f"  Wrote {card}")

    if promote:
        served = os.path.join(out_dir, "nifty_model.onnx")
        with open(served, "wb") as f:
            f.write(onnx_model.SerializeToString())
        print(f"  PROMOTED to {served} (now served by the app)")
    else:
        print("  Not promoted. Re-run with --promote once shadow-comparison passes.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="model version tag, e.g. 2")
    parser.add_argument("--promote", action="store_true", help="also overwrite the served nifty_model.onnx")
    parser.add_argument("--out-dir", default="src/main/resources")
    args = parser.parse_args()

    X, y = build_dataset()
    mean_acc, mean_auc, baseline = walk_forward_eval(X, y)

    print("\nStep 4: Training final model on all data...")
    final = GradientBoostingClassifier(random_state=42)
    final.fit(X, y)
    print(classification_report(y, final.predict(X)))

    export(final, args.version, args.out_dir, mean_acc, mean_auc, baseline, X.shape[0], args.promote)


if __name__ == "__main__":
    main()
