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
