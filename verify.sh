#!/usr/bin/env bash
#
# End-to-end smoke test: starts the app in safe simulated mode (no broker, no live orders),
# drives a few collection cycles, and asserts the engine generates a trade and serves every
# endpoint. Exits non-zero if any check fails.
#
# Prereqs: Postgres(5432) + Redis(6379) running (docker-compose up -d), Java 21, Maven.
# Usage:   ./verify.sh            # builds, starts, verifies, tears down
#          BASE_URL=... ./verify.sh   # verify an already-running instance
#          COLLECT_TICKS=20 GATING=40 ./verify.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
GATING="${GATING:-45}"
COLLECT_TICKS="${COLLECT_TICKS:-15}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

PASS=0; FAIL=0
check() { if [ "$2" -eq 0 ]; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
http() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
tcp_up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3>&- ; }

echo "== 0. Infra check (Postgres 5432, Redis 6379) =="
if ! tcp_up 5432 || ! tcp_up 6379; then
  if command -v docker >/dev/null 2>&1 && [ -f docker-compose.yml ]; then
    echo "  starting postgres/redis via docker-compose..."; docker-compose up -d; sleep 6
  else
    echo "  ERROR: Postgres/Redis not reachable and docker unavailable. Start them and retry."; exit 1
  fi
fi

# Dummy secrets so the app boots without a real .env (simulated mode needs none of them).
[ -f .env ] && { set -a; . ./.env; set +a; }
export TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-dummy}"
export TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-0}"
export GEMINI_API_KEY="${GEMINI_API_KEY:-}"
export ANGEL_ONE_API_KEY="${ANGEL_ONE_API_KEY:-dummy}"
export ANGEL_ONE_CLIENT_CODE="${ANGEL_ONE_CLIENT_CODE:-dummy}"
export ANGEL_ONE_PASSWORD="${ANGEL_ONE_PASSWORD:-dummy}"
export ANGEL_ONE_TOTP_KEY="${ANGEL_ONE_TOTP_KEY:-dummy}"

APP_PID=""
cleanup() { [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null; }
trap cleanup EXIT

if [ "$(http "$BASE_URL/api/v1/health")" = "200" ]; then
  echo "== Backend already running — verifying it as-is =="
else
  echo "== 1. Build jar =="
  mvn -q package -DskipTests || { echo "  build failed"; exit 1; }
  JAR="$(ls target/*-SNAPSHOT.jar 2>/dev/null | grep -v original | head -1)"
  [ -z "$JAR" ] && JAR="$(ls target/*.jar 2>/dev/null | grep -v original | head -1)"
  [ -z "$JAR" ] && { echo "  no jar found in target/"; exit 1; }

  echo "== 2. Start backend (simulated provider, orders OFF, gating=$GATING) =="
  java -jar "$JAR" \
    --nifty.collector.provider=simulated \
    --nifty.collector.market-hours-only=false \
    --nifty.gating-threshold="$GATING" \
    --nifty.order-execution.enabled=false \
    --nifty.risk.trading-enabled=true \
    --nifty.telegram.enabled=false \
    > /tmp/verify-app.log 2>&1 &
  APP_PID=$!
  echo "  app pid=$APP_PID (logs: /tmp/verify-app.log)"
  printf "  waiting for health"
  for _ in $(seq 1 60); do
    [ "$(http "$BASE_URL/api/v1/health")" = "200" ] && { echo " — UP"; break; }
    kill -0 "$APP_PID" 2>/dev/null || { echo " — app exited early:"; tail -25 /tmp/verify-app.log; exit 1; }
    printf "."; sleep 2
  done
fi

echo "== 3. Health & data endpoints =="
[ "$(http "$BASE_URL/api/v1/health")" = "200" ]; check "GET /health -> 200" $?

echo "== 4. Drive $COLLECT_TICKS collection cycles =="
for _ in $(seq 1 "$COLLECT_TICKS"); do curl -s -X POST "$BASE_URL/api/v1/market/collect" >/dev/null; sleep 2; done

printf "  polling /signals for a generated trade"
found=1
for _ in $(seq 1 15); do
  n=$(curl -s "$BASE_URL/api/v1/signals" | grep -o '"signalType"' | wc -l | tr -d ' ')
  if [ "${n:-0}" -gt 0 ]; then found=0; echo " — $n signal(s)"; break; fi
  printf "."; sleep 2
done
check "A trade signal was generated (the core goal)" $found

[ "$(http "$BASE_URL/api/v1/market/latest")" = "200" ];  check "GET /market/latest -> 200" $?
[ "$(http "$BASE_URL/api/v1/options/latest")" = "200" ]; check "GET /options/latest -> 200" $?
[ "$(http "$BASE_URL/api/v1/signals")" = "200" ];        check "GET /signals -> 200" $?
[ "$(http "$BASE_URL/api/v1/analytics/summary")" = "200" ]; check "GET /analytics/summary -> 200" $?

echo "== 5. Backtest & generators =="
curl -s -X POST "$BASE_URL/api/v1/analytics/backtest/run?start=2000-01-01T00:00:00&end=2100-01-01T00:00:00" | grep -q '"status"'
check "POST /analytics/backtest/run returns a result" $?
[ "$(http -X POST "$BASE_URL/api/v1/news/generate")" = "200" ]; check "POST /news/generate -> 200" $?

echo "== 6. Request validation =="
[ "$(http "$BASE_URL/api/v1/reports/history?type=X&page=-1")" = "400" ]; check "invalid page=-1 -> 400" $?

echo "== 7. A signal embodies the goal (2% target + quantity) =="
if command -v python3 >/dev/null 2>&1; then
  python3 - "$BASE_URL" <<'PY'
import sys, json, urllib.request
base = sys.argv[1]
data = json.load(urllib.request.urlopen(base + "/api/v1/signals"))
ok = False
if data:
    s = data[0]
    entry, t2, sl, q = s.get("entry"), s.get("target2"), s.get("stopLoss"), s.get("quantity")
    print(f"  entry={entry} target2={t2} (~{entry*1.02:.2f} expected) stopLoss={sl} qty={q}")
    ok = bool(entry and t2 and q) and abs(t2 - entry*1.02) < 0.5 and sl < entry
sys.exit(0 if ok else 1)
PY
  check "signal target2 ~= entry x 1.02 and quantity set" $?
else
  echo "  (python3 not found — skipping JSON assertion)"
fi

echo
echo "==================== RESULT ===================="
echo "PASS=$PASS  FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED — see /tmp/verify-app.log"; fi
exit "$FAIL"
