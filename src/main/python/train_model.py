import os
import subprocess
import sys

# Ensure required libraries are installed
required_libs = ["yfinance", "pandas", "numpy", "scikit-learn", "skl2onnx", "onnx", "onnxruntime", "optuna"]
for lib in required_libs:
    try:
        __import__(lib)
    except ImportError:
        print(f"Installing missing dependency: {lib}")
        subprocess.check_call([sys.executable, "-m", "pip", "install", lib])

import yfinance as yf
import pandas as pd
import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score
import optuna
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

def main():
    print("Step 1: Downloading 2 years of Nifty 50 1-hour data and daily India VIX...")
    # yfinance allows max 730d for 1h interval
    nifty = yf.download("^NSEI", period="730d", interval="1h")
    vix = yf.download("^INDIAVIX", period="730d", interval="1d")
    nifty_daily = yf.download("^NSEI", period="730d", interval="1d")

    # Clean up multi-index columns if present
    if isinstance(nifty.columns, pd.MultiIndex):
        nifty.columns = nifty.columns.get_level_values(0)
    if isinstance(vix.columns, pd.MultiIndex):
        vix.columns = vix.columns.get_level_values(0)
    if isinstance(nifty_daily.columns, pd.MultiIndex):
        nifty_daily.columns = nifty_daily.columns.get_level_values(0)

    # Calculate daily parameters (Prev_Daily_Return)
    nifty_daily['Daily_Return'] = (nifty_daily['Close'] - nifty_daily['Open']) / nifty_daily['Open']
    nifty_daily['Prev_Daily_Return'] = nifty_daily['Daily_Return'].shift(1)

    # Set indices to date objects for clean merging
    nifty_daily_mapped = nifty_daily[['Prev_Daily_Return']].copy()
    nifty_daily_mapped.index = nifty_daily_mapped.index.date

    vix_mapped = vix[['Close']].rename(columns={'Close': 'VIX'}).copy()
    vix_mapped.index = vix_mapped.index.date

    # Align dates and join
    nifty['Date'] = nifty.index.date
    nifty = nifty.join(nifty_daily_mapped, on='Date')
    nifty = nifty.join(vix_mapped, on='Date')

    # Forward fill daily features to cover intraday hours
    nifty['VIX'] = nifty['VIX'].ffill()
    nifty['Prev_Daily_Return'] = nifty['Prev_Daily_Return'].ffill()

    print("Step 2: Calculating technical indicators and expanded features...")
    # Calculate RSI (14)
    delta = nifty['Close'].diff()
    gain = (delta.where(delta > 0, 0)).rolling(window=14).mean()
    loss = (-delta.where(delta < 0, 0)).rolling(window=14).mean()
    rs = gain / (loss + 1e-9)
    nifty['RSI'] = 100 - (100 / (1 + rs))

    # Calculate EMAs
    nifty['EMA20'] = nifty['Close'].ewm(span=20, adjust=False).mean()
    nifty['EMA50'] = nifty['Close'].ewm(span=50, adjust=False).mean()
    nifty['Spot_to_EMA20_Ratio'] = nifty['Close'] / nifty['EMA20']
    nifty['EMA20_to_EMA50_Ratio'] = nifty['EMA20'] / nifty['EMA50']

    # Bollinger Band Width
    nifty['BB_Mid'] = nifty['Close'].rolling(window=20).mean()
    nifty['BB_Std'] = nifty['Close'].rolling(window=20).std()
    nifty['BB_Width'] = (2.0 * nifty['BB_Std']) / (nifty['BB_Mid'] + 1e-9)

    # MACD Histogram
    nifty['EMA12'] = nifty['Close'].ewm(span=12, adjust=False).mean()
    nifty['EMA26'] = nifty['Close'].ewm(span=26, adjust=False).mean()
    nifty['MACD'] = nifty['EMA12'] - nifty['EMA26']
    nifty['Signal'] = nifty['MACD'].ewm(span=9, adjust=False).mean()
    nifty['MACD_Hist'] = nifty['MACD'] - nifty['Signal']

    # Volume Ratio
    nifty['Volume_SMA20'] = nifty['Volume'].rolling(window=20).mean()
    nifty['Volume_Ratio'] = nifty['Volume'] / (nifty['Volume_SMA20'] + 1e-9)

    # Target: 1 if next hour's close > current hour's close, else 0
    nifty['Target'] = (nifty['Close'].shift(-1) > nifty['Close']).astype(int)

    # Drop NaNs
    df_clean = nifty.dropna()

    # Define features
    features = [
        'RSI', 
        'Spot_to_EMA20_Ratio', 
        'EMA20_to_EMA50_Ratio', 
        'VIX', 
        'Prev_Daily_Return',
        'BB_Width',
        'MACD_Hist',
        'Volume_Ratio'
    ]
    
    X = df_clean[features].astype(np.float32).values
    y = df_clean['Target'].values

    print(f"Features list: {features}")
    print(f"Preprocessed dataset shape: {X.shape}")

    # Chronological split (no shuffle for time series)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, shuffle=False)

    print("\nStep 3: Running Optuna hyperparameter optimization...")
    # Disable optuna logs to keep output clean
    optuna.logging.set_verbosity(optuna.logging.WARNING)
    
    def objective(trial):
        params = {
            'n_estimators': trial.suggest_int('n_estimators', 50, 250),
            'learning_rate': trial.suggest_float('learning_rate', 0.01, 0.15),
            'max_depth': trial.suggest_int('max_depth', 3, 7),
            'subsample': trial.suggest_float('subsample', 0.6, 1.0),
            'random_state': 42
        }
        # Train on a sub-split to find best generalized validation score
        X_tr, X_val, y_tr, y_val = train_test_split(X_train, y_train, test_size=0.2, random_state=42, shuffle=False)
        model = GradientBoostingClassifier(**params)
        model.fit(X_tr, y_tr)
        preds = model.predict(X_val)
        return accuracy_score(y_val, preds)

    study = optuna.create_study(direction='maximize')
    study.optimize(objective, n_trials=25)
    
    best_params = study.best_params
    best_params['random_state'] = 42
    print(f"Best hyperparameters found: {best_params}")

    print("\nStep 4: Training final Gradient Boosting model...")
    final_model = GradientBoostingClassifier(**best_params)
    final_model.fit(X_train, y_train)

    # Evaluate model
    y_pred = final_model.predict(X_test)
    accuracy = accuracy_score(y_test, y_pred)
    print(f"\nModel Validation Accuracy: {accuracy:.4f}")
    print("Classification Report:")
    print(classification_report(y_test, y_pred))

    print("\nStep 5: Exporting final model to ONNX format...")
    initial_type = [('input', FloatTensorType([None, len(features)]))]
    options = {id(final_model): {'zipmap': False}}
    onnx_model = convert_sklearn(final_model, initial_types=initial_type, options=options)

    output_dir = "src/main/resources"
    os.makedirs(output_dir, exist_ok=True)
    onnx_path = os.path.join(output_dir, "nifty_model.onnx")
    
    with open(onnx_path, "wb") as f:
        f.write(onnx_model.SerializeToString())
    
    print(f"Successfully saved ONNX model to: {os.path.abspath(onnx_path)}")

if __name__ == '__main__':
    main()
