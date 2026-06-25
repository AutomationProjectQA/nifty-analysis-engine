# Real-Time Tick Streaming (Angel One SmartWebSocketV2)

Adds a live index feed (Nifty **spot / future / VIX**) on top of the 1-minute REST collector.
The collector keeps running (it computes indicators + persists history); streaming just pushes
fresh ticks to the portal in between, so the dashboard's spot/future/VIX update tick-by-tick.

## Enable
```yaml
nifty:
  collector:
    provider: "angelone"     # required — streaming needs a live broker session
  stream:
    enabled: true            # default false
    url: "wss://smartapisocket.angelone.in/smart-stream"
```
When `enabled: false` (default) nothing changes — the `AngelOneStreamClient` bean isn't even created.

## How it works
- On startup (`ApplicationReadyEvent`), `AngelOneStreamClient` logs in (reuses `AngelOneDataClient`
  session — now also capturing the **feedToken**), opens the WebSocket with the required headers
  (`Authorization`, `x-api-key`, `x-client-code`, `x-feed-token`), and subscribes in **LTP mode**
  to the Nifty 50, India VIX, and current-month future tokens.
- Each binary frame is parsed (`parseTick`), the value lands in `MarketTickCache`, and a
  `{niftySpot, niftyFuture, indiaVix, ts}` payload is pushed to **`/topic/tick`** over STOMP.
- The Dashboard subscribes to `/topic/tick` and merges ticks onto the latest full snapshot.
- Heartbeat: sends `ping` every 25s. Auto-reconnects (5s) on close/error.

## What's verified vs what needs live testing
- ✅ **Binary parser** — unit-tested offline (`AngelOneStreamClientTest`) against the documented
  LTP-mode layout (mode@0, exchangeType@1, token@2..26, LTP int64 paise @43).
- ⚠️ **Connection / auth / subscription / heartbeat** — needs a **valid feed token + market hours**
  to verify. I could not test the live socket here.

### Verify live (during market hours, with valid Angel One creds)
1. Set `provider: angelone`, `stream.enabled: true`, and ensure your real creds are in the env.
2. Start the backend and watch:
   ```
   Angel One streaming enabled — connecting to live feed...
   Angel stream connected; subscribed to 3 instruments.
   ```
3. Open the dashboard → the **Nifty Spot / Future / VIX** numbers should tick continuously
   (faster than once a minute). Header chip shows "Live • Streaming".
4. If you see `no live session/feed token available`, the login didn't return a feed token —
   check creds/TOTP. If it connects but no ticks arrive, the frame offsets may need adjusting for
   the mode/exchange — confirm against Angel's SmartWebSocketV2 docs and tweak `parseTick`.

## Option-chain streaming (SnapQuote)
Also streams **per-strike OI + LTP** for the CE/PE contracts ±10 strikes around ATM, in
**SnapQuote mode (3)**:
- `AngelOneStreamClient` subscribes the option tokens (mode 3) alongside the index (mode 1) and
  routes each binary frame by its mode byte. `parseSnapQuote` reads LTP@43, Volume@67, **OI@131**.
- Live OI lands in `OptionTickCache` and is pushed (throttled, default 1s) to **`/topic/optionsTick`**.
- The Option Chain page **merges** these live OI values by strike onto the last full snapshot —
  IV / PCR / Max Pain / OI-change stay from the 1-minute collect cycle (they're computed, not streamed).
- ⚠️ The SnapQuote OI offset (@131) follows the documented layout; **confirm live** — if OI looks
  wrong, adjust the offset in `parseSnapQuote` (unit-tested in `AngelOneStreamClientTest`).
- ATM is centred once at connect; re-centring as spot drifts intraday is a small follow-up.

## Design notes
- Persistence/indicators remain on the collect cadence by design (persisting every tick would
  flood Postgres). The stream is purely additive and cannot break the REST path.
