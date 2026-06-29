package com.nifty.analysis.collector.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nifty.analysis.service.MarketStreamPublisher;
import com.nifty.analysis.service.MarketTickCache;
import com.nifty.analysis.service.OptionTickCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Real-time feed via Angel One SmartWebSocketV2 (binary stream).
 * OPT-IN: only active when {@code nifty.stream.enabled=true} (and provider=angelone).
 *  - Index (mode 1 / LTP): spot, future, VIX  -> /topic/tick
 *  - Options (mode 3 / SnapQuote): per-strike OI + LTP -> /topic/optionsTick (throttled)
 *
 * <p><b>Verify live:</b> the binary frame offsets follow the documented SmartWebSocketV2 layout
 * (LTP @43, Volume @67, OI @131, paise/100). {@link #parseTick} and {@link #parseSnapQuote} are
 * unit-tested offline; the live socket/auth/offsets must be confirmed during market hours.
 */
@Service
@ConditionalOnProperty(name = "nifty.stream.enabled", havingValue = "true")
@Slf4j
public class AngelOneStreamClient {

    @Value("${nifty.stream.url:wss://smartapisocket.angelone.in/smart-stream}")
    private String streamUrl;

    @Value("${nifty.stream.option-push-throttle-ms:1000}")
    private long optionPushThrottleMs;

    // Max % a streamed index tick may deviate from the trusted REST reference before it is treated
    // as a likely misparse and dropped. Guards against wrong binary frame offsets reaching the UI.
    @Value("${nifty.stream.max-tick-deviation-pct:10.0}")
    private double maxTickDeviationPct;

    private final AngelOneDataClient angelOne;
    private final MarketTickCache tickCache;
    private final OptionTickCache optionTickCache;
    private final MarketStreamPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "angel-stream");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, String> indexTokenKind = new ConcurrentHashMap<>();
    private final Map<String, AngelOneDataClient.OptionStreamInstrument> optionTokenMeta = new ConcurrentHashMap<>();
    private volatile WebSocket webSocket;
    private volatile boolean running = true;
    private volatile long lastOptionPushMs = 0;

    public AngelOneStreamClient(AngelOneDataClient angelOne, MarketTickCache tickCache,
                                OptionTickCache optionTickCache, MarketStreamPublisher publisher) {
        this.angelOne = angelOne;
        this.tickCache = tickCache;
        this.optionTickCache = optionTickCache;
        this.publisher = publisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("Angel One streaming enabled — connecting to live feed...");
        connect();
        scheduler.scheduleAtFixedRate(this::heartbeat, 25, 25, TimeUnit.SECONDS);
    }

    private void connect() {
        if (!running) return;
        try {
            angelOne.ensureSession();
            String jwt = angelOne.getJwtToken();
            String feedToken = angelOne.getFeedToken();
            if (jwt == null || feedToken == null || "SIMULATED_JWT_TOKEN".equals(jwt)) {
                log.warn("Angel stream: no live session/feed token available — streaming inactive.");
                return;
            }

            List<AngelOneDataClient.StreamInstrument> index = angelOne.getStreamInstruments();
            indexTokenKind.clear();
            index.forEach(i -> indexTokenKind.put(i.token(), i.kind()));

            // Option contracts around the current ATM (one REST read for spot to centre strikes).
            List<AngelOneDataClient.OptionStreamInstrument> options = new ArrayList<>();
            try {
                double spot = angelOne.fetchMarketData().niftySpot();
                options = angelOne.getOptionStreamInstruments(spot);
                optionTokenMeta.clear();
                options.forEach(o -> optionTokenMeta.put(o.token(), o));
            } catch (Exception e) {
                log.warn("Angel stream: could not resolve option tokens: {}", e.getMessage());
            }

            final List<AngelOneDataClient.OptionStreamInstrument> optionInstruments = options;
            httpClient.newWebSocketBuilder()
                    .header("Authorization", jwt)
                    .header("x-api-key", angelOne.getApiKey())
                    .header("x-client-code", angelOne.getClientCode())
                    .header("x-feed-token", feedToken)
                    .buildAsync(URI.create(streamUrl), new FeedListener())
                    .thenAccept(ws -> {
                        this.webSocket = ws;
                        // mode 1 = LTP (index), mode 3 = SnapQuote (options w/ OI)
                        Map<Integer, List<String>> indexTokens = new LinkedHashMap<>();
                        index.forEach(i -> indexTokens.computeIfAbsent(i.exchangeType(), k -> new ArrayList<>()).add(i.token()));
                        sendSubscribe(ws, 1, indexTokens);
                        if (!optionInstruments.isEmpty()) {
                            List<String> optTokens = new ArrayList<>();
                            optionInstruments.forEach(o -> optTokens.add(o.token()));
                            sendSubscribe(ws, 3, Map.of(2, optTokens)); // 2 = NSE_FO
                        }
                        log.info("Angel stream connected; {} index + {} option instruments subscribed.",
                                index.size(), optionInstruments.size());
                    })
                    .exceptionally(ex -> {
                        log.warn("Angel stream connect failed: {}", ex.getMessage());
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Angel stream connect error: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void sendSubscribe(WebSocket ws, int mode, Map<Integer, List<String>> byExch) {
        List<Map<String, Object>> tokenList = new ArrayList<>();
        byExch.forEach((exch, tokens) -> tokenList.add(Map.of("exchangeType", exch, "tokens", tokens)));
        Map<String, Object> msg = Map.of(
                "correlationID", "nifty-stream-" + mode,
                "action", 1, // subscribe
                "params", Map.of("mode", mode, "tokenList", tokenList));
        try {
            ws.sendText(objectMapper.writeValueAsString(msg), true);
        } catch (Exception e) {
            log.warn("Angel stream subscribe (mode {}) failed: {}", mode, e.getMessage());
        }
    }

    private void heartbeat() {
        WebSocket ws = webSocket;
        if (ws != null) {
            try { ws.sendText("ping", true); } catch (Exception ignored) { /* reconnect handles it */ }
        }
    }

    private void scheduleReconnect() {
        if (running) scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private void routeFrame(byte[] data) {
        if (data == null || data.length < 1) return;
        int mode = data[0] & 0xFF;
        if (mode == 1) {
            Tick t = parseTick(data);
            if (t != null) onIndexTick(t);
        } else if (mode == 3) {
            SnapQuoteTick t = parseSnapQuote(data);
            if (t != null) onOptionTick(t);
        }
    }

    private void onIndexTick(Tick tick) {
        String kind = indexTokenKind.get(tick.token());
        if (kind == null) return;
        if (!isPlausible(kind, tick.ltp())) {
            return; // likely a misparsed frame — don't poison the cache/UI
        }
        switch (kind) {
            case "SPOT" -> tickCache.updateSpot(tick.ltp());
            case "FUTURE" -> tickCache.updateFuture(tick.ltp());
            case "VIX" -> tickCache.updateVix(tick.ltp());
            default -> { return; }
        }
        publisher.publishTick(tickCache.getNiftySpot(), tickCache.getNiftyFuture(), tickCache.getIndiaVix());
    }

    /**
     * Validates a streamed index value against the trusted per-cycle REST reference. A value that
     * is non-positive or deviates more than {@code maxTickDeviationPct} from the reference is
     * almost certainly a wrong binary-frame offset — drop it and log loudly so the offset can be
     * confirmed/fixed during the first live market session.
     */
    private boolean isPlausible(String kind, double ltp) {
        if (ltp <= 0) {
            log.warn("Angel stream: dropping non-positive {} tick ({}). Check frame offsets.", kind, ltp);
            return false;
        }
        if (!tickCache.hasReference()) {
            return true; // no reference yet (pre first REST cycle) — accept and let REST correct it
        }
        double ref = switch (kind) {
            case "SPOT" -> tickCache.getReferenceSpot();
            case "FUTURE" -> tickCache.getReferenceFuture();
            case "VIX" -> tickCache.getReferenceVix();
            default -> 0.0;
        };
        if (ref <= 0) return true; // no reference for this kind yet
        double deviationPct = Math.abs(ltp - ref) / ref * 100.0;
        if (deviationPct > maxTickDeviationPct) {
            log.warn("Angel stream: dropping implausible {} tick {} (ref {}, {}% off > {}% limit). " +
                            "Likely a wrong binary frame offset — verify SmartWebSocketV2 layout.",
                    kind, ltp, ref, Math.round(deviationPct), maxTickDeviationPct);
            return false;
        }
        return true;
    }

    private void onOptionTick(SnapQuoteTick tick) {
        AngelOneDataClient.OptionStreamInstrument meta = optionTokenMeta.get(tick.token());
        if (meta == null) return;
        if ("CE".equals(meta.optionType())) {
            optionTickCache.updateCe(meta.strike(), tick.oi(), tick.ltp());
        } else {
            optionTickCache.updatePe(meta.strike(), tick.oi(), tick.ltp());
        }
        long now = System.currentTimeMillis();
        if (now - lastOptionPushMs >= optionPushThrottleMs) { // throttle: many tokens tick constantly
            lastOptionPushMs = now;
            publisher.publishOptionTicks(optionTickCache.snapshot());
        }
    }

    // ---- Binary frame parsing (SmartWebSocketV2) ----

    /** Parsed LTP tick (index). */
    record Tick(int mode, int exchangeType, String token, double ltp) {}

    /** Parsed SnapQuote tick (option) — token, LTP, open interest, volume. */
    record SnapQuoteTick(String token, double ltp, long oi, long volume) {}

    private static String readToken(byte[] data) {
        int end = 2;
        while (end < 27 && data[end] != 0) end++;
        return new String(data, 2, end - 2, StandardCharsets.US_ASCII).trim();
    }

    /** LTP mode: mode@0, exchType@1, token@2..26, LTP int64 paise @43. */
    static Tick parseTick(byte[] data) {
        if (data == null || data.length < 51) return null;
        long ltpPaise = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong(43);
        return new Tick(data[0] & 0xFF, data[1] & 0xFF, readToken(data), ltpPaise / 100.0);
    }

    /** SnapQuote mode: token@2..26, LTP @43, Volume @67, Open Interest @131 (all int64 LE). */
    static SnapQuoteTick parseSnapQuote(byte[] data) {
        if (data == null || data.length < 139) return null; // need through OI (bytes 131..138)
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        double ltp = bb.getLong(43) / 100.0;
        long volume = bb.getLong(67);
        long oi = bb.getLong(131);
        return new SnapQuoteTick(readToken(data), ltp, oi, volume);
    }

    private class FeedListener implements WebSocket.Listener {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buffer.writeBytes(chunk);
            if (last) {
                try {
                    routeFrame(buffer.toByteArray());
                } catch (Exception e) {
                    log.debug("Angel stream: failed to parse frame: {}", e.getMessage());
                } finally {
                    buffer.reset();
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            ws.request(1); // "pong" / control text
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("Angel stream error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("Angel stream closed ({}). Reconnecting...", statusCode);
            scheduleReconnect();
            return null;
        }
    }
}
