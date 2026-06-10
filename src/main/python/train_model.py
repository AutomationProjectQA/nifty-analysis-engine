import os
import subprocess
import sys

# Ensure required libraries are installed
required_libs = ["yfinance", "pandas", "numpy", "scikit-learn", "skl2onnx", "onnx", "onnxruntime"]
for lib in required_libs:
    try:
        __import__(lib)
    except ImportError:
        print(f"Installing missing dependency: {lib}")
        subprocess.check_call([sys.executable, "-m", "pip", "install", lib])

import yfinance as yf
import pandas as pd
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

def main():
    print("Step 1: Downloading 10 years of historical Nifty 50 (^NSEI) and India VIX (^INDIAVIX) data...")
    # Fetch Nifty and VIX
    nifty = yf.download("^NSEI", period="10y", interval="1d")
    vix = yf.download("^INDIAVIX", period="10y", interval="1d")

    # Clean up multi-index columns if present (common in newer yfinance versions)
    if isinstance(nifty.columns, pd.MultiIndex):
        nifty.columns = nifty.columns.get_level_values(0)
    if isinstance(vix.columns, pd.MultiIndex):
        vix.columns = vix.columns.get_level_values(0)

    # Flatten dataframe and keep only Close and Volume
    nifty = nifty[['Open', 'High', 'Low', 'Close', 'Volume']].dropna()
    vix = vix[['Close']].rename(columns={'Close': 'VIX'}).dropna()

    # Merge datasets
    df = nifty.join(vix, how='inner').dropna()
    print(f"Total historical data points downloaded: {len(df)}")

    print("Step 2: Calculating technical indicators and features...")
    # Calculate RSI (14)
    delta = df['Close'].diff()
    gain = (delta.where(delta > 0, 0)).rolling(window=14).mean()
    loss = (-delta.where(delta < 0, 0)).rolling(window=14).mean()
    rs = gain / (loss + 1e-9)
    df['RSI'] = 100 - (100 / (1 + rs))

    # Calculate EMAs
    df['EMA20'] = df['Close'].ewm(span=20, adjust=False).mean()
    df['EMA50'] = df['Close'].ewm(span=50, adjust=False).mean()

    # Feature engineering matching our Java real-time agents
    df['Spot_to_EMA20_Ratio'] = df['Close'] / df['EMA20']
    df['EMA20_to_EMA50_Ratio'] = df['EMA20'] / df['EMA50']
    df['VIX_Level'] = df['VIX']
    
    # Daily return momentum
    df['Daily_Return'] = (df['Close'] - df['Open']) / df['Open']
    df['Prev_Daily_Return'] = df['Daily_Return'].shift(1)

    # Label generation:
    # Target = 1 if the next day's close is higher than today's close (Bullish trend next day)
    # Target = 0 otherwise
    df['Target'] = (df['Close'].shift(-1) > df['Close']).astype(int)

    # Drop NaNs created by rolling indicators and shifts
    df = df.dropna()

    # Define features and target
    features = ['RSI', 'Spot_to_EMA20_Ratio', 'EMA20_to_EMA50_Ratio', 'VIX_Level', 'Prev_Daily_Return']
    X = df[features].astype(np.float32).values
    y = df['Target'].values

    print(f"Features list: {features}")
    print(f"Preprocessed dataset shape: {X.shape}")

    # Split into train/test
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, shuffle=False)

    print("Step 3: Training Random Forest Classifier...")
    model = RandomForestClassifier(n_estimators=100, max_depth=6, random_state=42)
    model.fit(X_train, y_train)

    # Evaluate the model
    y_pred = model.predict(X_test)
    accuracy = accuracy_score(y_test, y_pred)
    print(f"\nModel Validation Accuracy: {accuracy:.4f}")
    print("Classification Report:")
    print(classification_report(y_test, y_pred))

    print("Step 4: Exporting model to ONNX format...")
    # Define input type (None stands for batch dimension, 5 is the number of features)
    initial_type = [('input', FloatTensorType([None, len(features)]))]
    
    # Convert using skl2onnx. We disable zipmap to return standard list/arrays for output
    options = {id(model): {'zipmap': False}}
    onnx_model = convert_sklearn(model, initial_types=initial_type, options=options)

    # Write ONNX file
    output_dir = "../resources"
    os.makedirs(output_dir, exist_ok=True)
    onnx_path = os.path.join(output_dir, "nifty_model.onnx")
    
    with open(onnx_path, "wb") as f:
        f.write(onnx_model.SerializeToString())
    
    print(f"Successfully saved ONNX model to: {os.path.abspath(onnx_path)}")

if __name__ == "__main__":
    main()
