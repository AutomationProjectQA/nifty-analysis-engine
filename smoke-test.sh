#!/usr/bin/env bash
# Post-deploy smoke test — asserts the running app is serving REAL, sane, instrument-correct data.
# Usage:  ./smoke-test.sh https://your-domain        (defaults to http://localhost:8080)
# Exit 0 = all critical checks pass; non-zero = something is wrong (fail the deploy).
#
# Catches the production-bug classes we hit: wrong instrument, simulated/insane spot, dead endpoints,
# missing option LTP. Run it right after every deploy (ideally during market hours for the live checks).

set -uo pipefail
BASE="${1:-http://localhost:8080}"
FAILS=0

# Fetch a URL; print status. Returns body on stdout.
get() { curl -s -m 10 -w '\n%{http_code}' "$BASE$1"; }

pass() { echo "  ✅ $1"; }
fail() { echo "  ❌ $1"; FAILS=$((FAILS+1)); }

# jq-free JSON field extraction via python3.
json() { python3 -c "import sys,json;
try:
  d=json.load(sys.stdin); v=d
  for k in '$1'.split('.'): v=v[k] if isinstance(v,dict) else v[int(k)]
  print(v)
except Exception: print('')"; }

echo "Smoke test against: $BASE"
echo "--- connectivity ---"
RESP=$(get /api/v1/health); CODE=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
[ "$CODE" = "200" ] && pass "GET /api/v1/health 200" || fail "GET /api/v1/health returned $CODE (backend unreachable?)"

echo "--- data health verdict ---"
RESP=$(get /api/v1/health/data); CODE=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
STATUS=$(echo "$BODY" | json status)
if [ "$STATUS" = "HEALTHY" ]; then pass "data health HEALTHY";
elif [ "$STATUS" = "DEGRADED" ]; then fail "data health DEGRADED: $(echo "$BODY" | python3 -c 'import sys,json;print("; ".join(json.load(sys.stdin).get("problems",[])))' 2>/dev/null)";
else fail "data health endpoint returned no status (code $CODE)"; fi

echo "--- /market/latest is NIFTY + sane ---"
RESP=$(get /api/v1/market/latest); BODY=$(echo "$RESP" | sed '$d')
INSTR=$(echo "$BODY" | json instrument); SPOT=$(echo "$BODY" | json niftySpot)
[ "$INSTR" = "NIFTY" ] && pass "instrument=NIFTY" || fail "instrument='$INSTR' (expected NIFTY — instrument-scoping/BANKNIFTY leak)"
if [ -n "$SPOT" ]; then
  python3 -c "import sys; s=float('$SPOT'); sys.exit(0 if 15000<=s<=50000 else 1)" \
    && pass "niftySpot $SPOT in sane band" || fail "niftySpot $SPOT outside [15000,50000]"
  # The exact mock-default tell:
  python3 -c "import sys; sys.exit(1 if abs(float('$SPOT')-23510.50)<0.01 else 0)" \
    || fail "niftySpot == 23510.50 (frontend MOCK default — backend returned no data)"
else fail "no niftySpot in /market/latest"; fi

echo "--- feed source ---"
RESP=$(get /api/v1/market/feed-status); BODY=$(echo "$RESP" | sed '$d')
DS=$(echo "$BODY" | json dataSource)
[ "$DS" = "LIVE" ] && pass "feed dataSource LIVE" || echo "  ⚠️  feed dataSource=$DS (expected LIVE during market hours; OK off-hours)"

echo "--- option chain present + has LTP ---"
RESP=$(get '/api/v1/options/latest'); BODY=$(echo "$RESP" | sed '$d')
HASLTP=$(echo "$BODY" | python3 -c 'import sys,json
try:
  d=json.load(sys.stdin); print("yes" if isinstance(d,list) and d and any((r.get("ceLtp") or 0)>0 for r in d) else "no")
except Exception: print("err")' 2>/dev/null)
case "$HASLTP" in
  yes) pass "option chain has CE LTP values";;
  no)  echo "  ⚠️  option chain present but no CE LTP (>0) — OK off-hours / simulated";;
  *)   fail "option chain endpoint returned no usable data";;
esac

echo "----------------------------------------"
if [ "$FAILS" -eq 0 ]; then echo "✅ SMOKE TEST PASSED"; exit 0; else echo "❌ SMOKE TEST FAILED ($FAILS critical check(s))"; exit 1; fi
