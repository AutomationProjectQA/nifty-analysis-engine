# Nifty Analysis Engine - Execution Guide

This document outlines instructions on how to build, run, and interact with the Nifty Analysis Engine, along with sample outputs from the system's endpoints.

---

## 1. How to Run the Project

### Prerequisites
- **Java 21** installed.
- **Maven 3.9+** installed.
- **Docker** and **Docker Compose** (to start PostgreSQL 16 & Redis 7).

### Step 1: Start External Services (Database & Cache)
Run the following command from the root directory containing `docker-compose.yml`:
```bash
docker-compose up -d
```
This spins up:
- PostgreSQL on port `5432` (database name: `nifty`, credentials: `postgres`/`postgres`).
- Redis on port `6379`.

### Step 2: Run the Spring Boot App
Start the application using Maven:
```bash
mvn clean spring-boot:run
```
Once started, Flyway automatically builds the tables and seeds default weights. The application listens on port `8080`.

---

## 2. API Endpoints & Output Formats

### 1. Trigger Data Collection
Force a real-time data collection tick (queries Simulated clients, runs indicator formulas, evaluates signals):
- **Request:** `POST http://localhost:8080/api/v1/market/collect`
- **Output:**
```text
Data collection triggered successfully
```

### 2. Get Latest Market Snapshot
Retrieve the latest parsed market prices and technical indicators (EMA, RSI, VWAP):
- **Request:** `GET http://localhost:8080/api/v1/market/latest`
- **Output:**
```json
{
  "id": 1,
  "snapshotTime": "2026-06-03T14:20:00",
  "niftySpot": 23510.50,
  "niftyFuture": 23548.20,
  "indiaVix": 13.40,
  "volume": 125000.0,
  "ema20": 23495.12,
  "ema50": 23478.45,
  "rsi": 58.20,
  "vwap": 23502.10
}
```

### 3. Get Latest Option Chain
Fetch the latest gathered option chain snapshot containing strikes, Put/Call open interest, and PCR values:
- **Request:** `GET http://localhost:8080/api/v1/options/latest`
- **Output:**
```json
[
  {
    "id": 101,
    "snapshotTime": "2026-06-03T14:20:00",
    "strikePrice": 23500,
    "ceOi": 1120000,
    "peOi": 1450000,
    "ceOiChange": 45000,
    "peOiChange": 120000,
    "iv": 12.80,
    "pcr": 1.29,
    "maxPain": 23500.00
  }
]
```

### 4. Trigger Weights Optimization
Force the adaptive self-learning agent to review historical trade results and optimize active confidence factor weights:
- **Request:** `POST http://localhost:8080/api/v1/analytics/optimize`
- **Output:**
```text
Weights optimization complete
```

### 5. Run Historical Backtest
Replay past database records to compute signals, entry levels, targets, and exit timestamps:
- **Request:** `POST http://localhost:8080/api/v1/analytics/backtest/run?start=2026-06-01T09:15:00&end=2026-06-03T15:30:00`
- **Output:**
```json
{
  "status": "SUCCESS",
  "totalSignals": 15,
  "target1Hits": 10,
  "target2Hits": 7,
  "stopLossHits": 4,
  "totalPnL": 195.0,
  "winRatePercentage": 56.67
}
```

### 6. Get Performance Summary
Retrieve global backtest / live results statistics (trade counts, outcome ratios, and P&L):
- **Request:** `GET http://localhost:8080/api/v1/analytics/summary`
- **Output:**
```json
{
  "totalTrades": 15,
  "target1Hits": 10,
  "target2Hits": 7,
  "stopLossHits": 4,
  "expiredTrades": 4,
  "totalProfitLossPoints": 195.0,
  "winRatePercentage": 56.67
}
```

### 7. Trigger a Test Alert (Gemini + Telegram Verification)
Verify that your Telegram Bot, Chat ID, and Gemini API Key are correctly configured by generating a mock trade signal with an AI-generated thesis summary:
- **Request:** `GET http://localhost:8080/api/v1/test-alert`
- **Output:**
```json
{
  "status": "SUCCESS",
  "message": "Test alert triggered successfully",
  "geminiExplanation": "The trade thesis for BUY_CE on strike 23400 is justified by robust trend confirmation (85%) and substantial Put writing support (90%) acting as a price floor. Furthermore, price action is consolidating above the key 20-period EMA breakout level. This aligns with structural parameters for bullish momentum.",
  "usingFallback": false,
  "telegramEnabled": true
}
```

---

## 3. Angel One SmartAPI Live Order Execution

The system features real-time order placement via the Angel One SmartAPI. When a trade signal confidence meets or exceeds the threshold (configured at **60.0%**), a Robo (bracket) limit order is placed directly.

### Configuration Properties

Specify the following under the `nifty` block in your `application.yml` file:
```yaml
nifty:
  gating-threshold: 60.0 # Confidence gating threshold
  order-execution:
    enabled: true        # Enable live or simulated orders
    lot-size: 65         # Nifty option lot size
    risk-per-trade-percent: 100.0 # Allocate 100% of wallet balance
```

### Key Execution Behaviors

1. **RMS Balance Allocation**:
   The engine requests available cash balance from the Angel One RMS endpoint (`GET .../getRMS`). It calculates the maximum possible number of lots based on 100% allocation of the available wallet cash:
   $$\text{Lots} = \lfloor \frac{\text{Wallet Cash}}{\text{Entry Premium} \times 65} \rfloor$$
2. **2% Profit Target (Square-off)**:
   The system sets the profit target (square-off points offset) at exactly **2%** of the option entry premium price, rounded to the nearest tick size of `0.05`. Symmetrical 2% stop-loss offsets are applied.
3. **Simulation Mode Fallback**:
   If the API credentials are not active or `SIMULATED_JWT_TOKEN` is loaded, the service falls back to **Simulation Mode**. A simulated trade signal is evaluated, sized, and printed to logs and Telegram, without hitting live broker order endpoints.

---

## 4. Machine Learning Model Integration (ONNX Hybrid)

The system utilizes a hybrid model structure. A quantitative Machine Learning model (Random Forest) trained on **10 years of Nifty 50 historical data** generates the raw trade confidence, while the Gemini LLM acts as the macro/event risk gate.

### Training the Model
To retrain or customize the ML model, you can run the python pipeline:

1. **Install Dependencies:**
   Make sure you have python libraries installed in your virtual environment:
   ```bash
   python3 -m pip install yfinance pandas numpy scikit-learn skl2onnx onnx onnxruntime
   ```

2. **Run Training Script:**
   Execute the python script from the python folder:
   ```bash
   cd src/main/python
   python3 train_model.py
   ```
   This script:
   - Downloads 10 years of daily `^NSEI` (Nifty 50) and `^INDIAVIX` historical data.
   - Generates features (`RSI`, `Spot_to_EMA20_Ratio`, `EMA20_to_EMA50_Ratio`, `VIX_Level`, and `Prev_Daily_Return`).
   - Trains a `RandomForestClassifier` predicting whether the next day's close will be bullish (upward trend).
   - Convers the classifier to ONNX format with ZipMap disabled and writes it directly to `src/main/resources/nifty_model.onnx`.

### Java Execution
On application startup, the `OnnxModelService` automatically loads the packaged `nifty_model.onnx` file from the classpath.
- For every evaluation tick, `TechnicalAgent` extracts the same 5 normalized features from the current snapshot.
- The `OnnxModelService` executes model inference to calculate the bullish/bearish probability, which represents the raw trade entry confidence.
- This probability is forwarded to `CriticAgent` for risk penalties before gating trade order execution.


