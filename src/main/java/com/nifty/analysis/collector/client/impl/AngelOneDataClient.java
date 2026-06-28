package com.nifty.analysis.collector.client.impl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.nifty.analysis.collector.client.MarketDataClient;
import com.nifty.analysis.collector.client.OptionChainClient;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.dto.OptionSnapshotDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "nifty.collector.provider", havingValue = "angelone")
@RequiredArgsConstructor
@Slf4j
public class AngelOneDataClient implements MarketDataClient, OptionChainClient,
        com.nifty.analysis.util.ExpiryInstrumentSource {

    @Value("${nifty.angelone.api-key:}")
    private String apiKey;

    @Value("${nifty.angelone.client-code:}")
    private String clientCode;

    @Value("${nifty.angelone.password:}")
    private String password;

    @Value("${nifty.angelone.totp-key:}")
    private String totpKey;

    private final WebClient.Builder webClientBuilder;
    private final com.nifty.analysis.service.DataFeedStatus dataFeedStatus;

    private String jwtToken;
    private String feedToken; // required by the streaming WebSocket (SmartWebSocketV2)
    private final Map<String, ScripInfo> scripMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastCeOiMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastPeOiMap = new ConcurrentHashMap<>();
    private boolean scripMasterLoaded = false;

    // For seeding OI baselines from the DB after a restart (so the first cycle's OI-change
    // isn't a misleading 0). Optional field injection keeps the constructor unchanged.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nifty.analysis.repository.OptionSnapshotRepository optionSnapshotRepository;
    private volatile boolean oiBaselineSeeded = false;

    // For backing out real per-strike IV from live option LTP (volatility smile).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nifty.analysis.service.BlackScholesService blackScholesService;

    // Proactively refresh the JWT well before Angel One's ~24h expiry, so calls don't start
    // failing mid-session (which would silently fall back to simulated data).
    private static final long TOKEN_TTL_MS = 8L * 60 * 60 * 1000;
    private volatile long tokenIssuedAtMs = 0;

    private record ScripInfo(String token, String symbol, String exchSeg, String expiry, double strike) {}

    @PostConstruct
    public void init() {
        // Load scrip master asynchronously in a background thread to prevent blocking Spring Boot startup
        new Thread(this::loadScripMaster).start();
    }

    private void loadScripMaster() {
        log.info("Starting background download of Angel One Scrip Master...");
        try {
            URL url = new URL("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json");
            try (InputStream is = url.openStream()) {
                JsonFactory factory = new JsonFactory();
                try (JsonParser parser = factory.createParser(is)) {
                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new IllegalStateException("Expected JSON array for scrip master");
                    }
                    
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        String token = null;
                        String symbol = null;
                        String name = null;
                        String expiry = null;
                        String strikeStr = null;
                        String exchSeg = null;
                        String instrumenttype = null;

                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String fieldName = parser.getCurrentName();
                            parser.nextToken();
                            if ("token".equals(fieldName)) token = parser.getText();
                            else if ("symbol".equals(fieldName)) symbol = parser.getText();
                            else if ("name".equals(fieldName)) name = parser.getText();
                            else if ("expiry".equals(fieldName)) expiry = parser.getText();
                            else if ("strike".equals(fieldName)) strikeStr = parser.getText();
                            else if ("exch_seg".equals(fieldName)) exchSeg = parser.getText();
                            else if ("instrumenttype".equals(fieldName)) instrumenttype = parser.getText();
                        }

                        // Filter Nifty 50 options, futures and index spot/VIX
                        if (name != null && (name.equals("NIFTY") || name.equals("INDIA VIX") || symbol.equals("Nifty 50"))) {
                            double strike = 0.0;
                            if (strikeStr != null && !strikeStr.isEmpty()) {
                                try {
                                    strike = Double.parseDouble(strikeStr);
                                } catch (NumberFormatException ignored) {}
                            }
                            ScripInfo info = new ScripInfo(token, symbol, exchSeg, expiry, strike);
                            scripMap.put(symbol, info);
                        }
                    }
                }
            }
            scripMasterLoaded = true;
            log.info("Successfully loaded and parsed {} Nifty-related scrips from Angel One master.", scripMap.size());
        } catch (Exception e) {
            log.error("Failed to download or parse Angel One Scrip Master. Fallback to hardcoded tokens.", e);
            // Seed essential defaults
            scripMap.put("Nifty 50", new ScripInfo("99926000", "Nifty 50", "NSE", "", 0.0));
            scripMap.put("INDIA VIX", new ScripInfo("99926017", "INDIA VIX", "NSE", "", 0.0));
            scripMasterLoaded = true;
        }
    }

    private synchronized void ensureAuthenticated() {
        // Re-authenticate if there's no token OR the current one is past its refresh TTL.
        if (jwtToken != null && (System.currentTimeMillis() - tokenIssuedAtMs) < TOKEN_TTL_MS) {
            return;
        }

        if (apiKey.isEmpty() || clientCode.isEmpty() || password.isEmpty() || totpKey.isEmpty()) {
            log.warn("Angel One SmartAPI credentials are not fully configured. Using simulated fallback token.");
            jwtToken = "SIMULATED_JWT_TOKEN";
            tokenIssuedAtMs = System.currentTimeMillis();
            return;
        }

        try {
            log.info("Generating TOTP code for Angel One Authentication...");
            String totpCode = getTOTPCode(totpKey);
            
            Map<String, String> request = new HashMap<>();
            request.put("clientcode", clientCode);
            request.put("password", password);
            request.put("totp", totpCode);

            log.info("Requesting login session from Angel One SmartAPI...");
            String response = webClientBuilder.build().post()
                    .uri("https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", "192.168.1.100")
                    .header("X-ClientPublicIP", "192.168.1.100")
                    .header("X-MACAddress", "02:00:00:00:00:00")
                    .header("X-PrivateKey", apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(res -> {
                        if (Boolean.TRUE.equals(res.get("status"))) {
                            Map<String, Object> data = (Map<String, Object>) res.get("data");
                            this.feedToken = (String) data.get("feedToken"); // for the streaming socket
                            return (String) data.get("jwtToken");
                        }
                        throw new RuntimeException("SmartAPI Login failed: " + res.get("message"));
                    })
                    .block();

            if (response != null) {
                this.jwtToken = response;
                log.info("Successfully authenticated with Angel One SmartAPI!");
            }
        } catch (Exception e) {
            log.error("Angel One authentication failed. Falling back to simulated authentication token.", e);
            jwtToken = "SIMULATED_JWT_TOKEN";
        }
        tokenIssuedAtMs = System.currentTimeMillis(); // stamp success or fallback; TTL governs the next retry
    }

    @Override
    public MarketSnapshotDto fetchMarketData(String instrument) {
        ensureAuthenticated();

        // Live quote-fetch is wired for NIFTY only. Other instruments fall back to simulated data
        // (which block-on-simulated-data refuses to trade) until their live tokens/symbols are
        // resolved + validated on the VM. NIFTY behaviour below is unchanged.
        if (!"NIFTY".equals(instrument)) {
            log.warn("Live Angel One fetch not yet wired for {}. Returning SIMULATED fallback (won't trade live).", instrument);
            return getSimulatedFallbackMarketData();
        }

        // If credentials are simulated, return simulated data
        if ("SIMULATED_JWT_TOKEN".equals(jwtToken)) {
            return getSimulatedFallbackMarketData();
        }

        try {
            // Find current month futures scrip info
            String niftyFutureSymbol = findCurrentMonthFutureSymbol();
            String futToken = "0";
            if (niftyFutureSymbol != null && scripMap.containsKey(niftyFutureSymbol)) {
                futToken = scripMap.get(niftyFutureSymbol).token();
            }

            // Find Spot and VIX tokens dynamically from scripMap
            String spotToken = "99926000";
            String spotExch = "NSE";
            ScripInfo spotInfo = scripMap.get("Nifty 50");
            if (spotInfo != null) {
                spotToken = spotInfo.token();
                spotExch = spotInfo.exchSeg();
            }

            String vixToken = "99926017";
            String vixExch = "NSE";
            ScripInfo vixInfo = scripMap.get("INDIA VIX");
            if (vixInfo != null) {
                vixToken = vixInfo.token();
                vixExch = vixInfo.exchSeg();
            }

            Map<String, Object> request = new HashMap<>();
            request.put("mode", "FULL");
            Map<String, List<String>> exchangeTokens = new HashMap<>();
            
            // Add Spot and VIX to their respective exchanges
            exchangeTokens.computeIfAbsent(spotExch, k -> new ArrayList<>()).add(spotToken);
            exchangeTokens.computeIfAbsent(vixExch, k -> new ArrayList<>()).add(vixToken);

            if (!"0".equals(futToken)) {
                exchangeTokens.computeIfAbsent("NFO", k -> new ArrayList<>()).add(futToken);
            }
            request.put("exchangeTokens", exchangeTokens);

            Map<String, Object> response = webClientBuilder.build().post()
                    .uri("https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/")
                    .header("Authorization", "Bearer " + jwtToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", "192.168.1.100")
                    .header("X-ClientPublicIP", "192.168.1.100")
                    .header("X-MACAddress", "02:00:00:00:00:00")
                    .header("X-PrivateKey", apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> fetched = (List<Map<String, Object>>) data.get("fetched");
                
                double spot = 23500.0;
                double vix = 13.5;
                double futures = 23530.0;
                double volume = 200000.0;

                for (Map<String, Object> quote : fetched) {
                    String token = (String) quote.get("symbolToken");
                    if (token == null) token = (String) quote.get("token");
                    
                    if (spotToken.equals(token)) {
                        spot = parseDouble(quote.get("ltp"));
                        double vol = parseDouble(quote.get("volume"));
                        if (vol == 0.0) vol = parseDouble(quote.get("tradeVolume"));
                        volume = vol;
                    } else if (vixToken.equals(token)) {
                        vix = parseDouble(quote.get("ltp"));
                    } else if (token != null && token.equals(futToken)) {
                        futures = parseDouble(quote.get("ltp"));
                    }
                }

                dataFeedStatus.update(true); // real live market data
                return new MarketSnapshotDto(spot, futures, vix, volume, com.nifty.analysis.util.TimeUtil.nowIst());
            }
        } catch (Exception e) {
            log.error("Failed to fetch market data from Angel One API. Using simulated fallback.", e);
        }
        return getSimulatedFallbackMarketData();
    }

    /**
     * Seeds the in-memory OI baselines from the last stored snapshot once after startup, so the
     * first live cycle computes a real OI-change instead of 0 (which would skip OI-based safety
     * gates right after a deploy). Best-effort: never blocks data collection.
     */
    private void seedOiBaselineIfNeeded() {
        if (oiBaselineSeeded || optionSnapshotRepository == null) {
            return;
        }
        try {
            java.time.LocalDateTime latest = optionSnapshotRepository.findLatestSnapshotTime();
            if (latest != null) {
                int seeded = 0;
                for (com.nifty.analysis.entity.OptionSnapshot o : optionSnapshotRepository.findBySnapshotTime(latest)) {
                    if (o.getStrikePrice() == null) continue;
                    if (o.getCeOi() != null) lastCeOiMap.put(o.getStrikePrice(), o.getCeOi());
                    if (o.getPeOi() != null) lastPeOiMap.put(o.getStrikePrice(), o.getPeOi());
                    seeded++;
                }
                log.info("Seeded OI baselines from {} stored strikes (snapshot {}).", seeded, latest);
            }
        } catch (Exception e) {
            log.warn("Could not seed OI baselines from DB: {}", e.getMessage());
        }
        oiBaselineSeeded = true;
    }

    @Override
    public List<OptionSnapshotDto> fetchOptionChain(String instrument) {
        ensureAuthenticated();

        if (!"NIFTY".equals(instrument)) {
            log.warn("Live Angel One option chain not yet wired for {}. Returning SIMULATED fallback.", instrument);
            return getSimulatedFallbackOptions();
        }

        if ("SIMULATED_JWT_TOKEN".equals(jwtToken)) {
            return getSimulatedFallbackOptions();
        }

        seedOiBaselineIfNeeded();

        try {
            double spot = fetchMarketData().niftySpot();
            int atmStrike = ((int) Math.round(spot / 50.0)) * 50;

            String expiryDateSymbolStr = findCurrentOptionExpiryDateSymbolStr();
            List<String> optionSymbols = new ArrayList<>();
            Map<String, Integer> strikeSymbolMap = new HashMap<>();

            // ±20 strikes (±1000 points) around ATM, like the NSE option chain view.
            for (int i = -20; i <= 20; i++) {
                int strike = atmStrike + (i * 50);
                String ceSymbol = "NIFTY" + expiryDateSymbolStr + strike + "CE";
                String peSymbol = "NIFTY" + expiryDateSymbolStr + strike + "PE";
                optionSymbols.add(ceSymbol);
                optionSymbols.add(peSymbol);
                strikeSymbolMap.put(ceSymbol, strike);
                strikeSymbolMap.put(peSymbol, strike);
            }

            List<String> tokens = new ArrayList<>();
            for (String sym : optionSymbols) {
                if (scripMap.containsKey(sym)) {
                    tokens.add(scripMap.get(sym).token());
                }
            }

            if (tokens.isEmpty()) {
                return getSimulatedFallbackOptions();
            }

            // Angel One caps a FULL quote at ~50 tokens/request, so fetch in batches and merge.
            List<Map<String, Object>> fetched = fetchFullQuoteNfo(tokens);

            if (!fetched.isEmpty()) {
                Map<Integer, OptionSnapshotDtoBuilder> builders = new HashMap<>();
                for (Map<String, Object> quote : fetched) {
                    String symbol = (String) quote.get("tradingSymbol");
                    Integer strike = strikeSymbolMap.get(symbol);
                    if (strike == null) continue;

                    OptionSnapshotDtoBuilder builder = builders.computeIfAbsent(strike, OptionSnapshotDtoBuilder::new);
                    boolean isCe = symbol.endsWith("CE");
                    long oi = parseLong(quote.get("opnInterest"));
                    if (oi == 0L) {
                        oi = parseLong(quote.get("openInterest"));
                    }

                    long volume = parseLong(quote.get("volume"));
                    if (volume == 0L) {
                        volume = parseLong(quote.get("tradeVolume"));
                    }

                    double ltp = parseDouble(quote.get("ltp"));

                    if (isCe) {
                        builder.ceOi = oi;
                        long lastCeOi = lastCeOiMap.getOrDefault(strike, 0L);
                        builder.ceOiChange = lastCeOi > 0 ? (oi - lastCeOi) : 0L;
                        lastCeOiMap.put(strike, oi);
                        builder.ceVolume = volume;
                        builder.ceLtp = ltp;
                    } else {
                        builder.peOi = oi;
                        long lastPeOi = lastPeOiMap.getOrDefault(strike, 0L);
                        builder.peOiChange = lastPeOi > 0 ? (oi - lastPeOi) : 0L;
                        lastPeOiMap.put(strike, oi);
                        builder.peVolume = volume;
                        builder.peLtp = ltp;
                    }
                }

                // Time to the current weekly expiry (Tuesday) for IV inversion.
                double years = com.nifty.analysis.util.TimeUtil.daysToWeeklyExpiry(
                        com.nifty.analysis.util.TimeUtil.todayIst()) / 365.0;

                List<OptionSnapshotDto> snapshots = new ArrayList<>();
                LocalDateTime now = com.nifty.analysis.util.TimeUtil.nowIst();
                for (OptionSnapshotDtoBuilder b : builders.values()) {
                    double pcr = b.ceOi > 0 ? (double) b.peOi / b.ceOi : 0.0;
                    snapshots.add(new OptionSnapshotDto(
                        b.strike,
                        b.ceOi,
                        b.peOi,
                        b.ceOiChange,
                        b.peOiChange,
                        solveStrikeIv(spot, b.strike, years, b.ceLtp, b.peLtp),
                        Math.round(pcr * 100.0) / 100.0,
                        (double) atmStrike,
                        b.ceVolume,
                        b.peVolume,
                        now
                    ));
                }
                return snapshots;
            }
        } catch (Exception e) {
            log.error("Failed to fetch option chain from Angel One API. Using simulated fallback.", e);
        }
        return getSimulatedFallbackOptions();
    }

    /** Fetches a FULL quote for NFO tokens in batches of 50 (Angel One's per-request cap), merged. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchFullQuoteNfo(List<String> tokens) {
        List<Map<String, Object>> merged = new ArrayList<>();
        final int batch = 50;
        for (int start = 0; start < tokens.size(); start += batch) {
            List<String> chunk = tokens.subList(start, Math.min(start + batch, tokens.size()));
            try {
                Map<String, Object> request = new HashMap<>();
                request.put("mode", "FULL");
                Map<String, List<String>> exchangeTokens = new HashMap<>();
                exchangeTokens.put("NFO", new ArrayList<>(chunk));
                request.put("exchangeTokens", exchangeTokens);

                Map<String, Object> response = webClientBuilder.build().post()
                        .uri("https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/")
                        .header("Authorization", "Bearer " + jwtToken)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("X-UserType", "USER")
                        .header("X-SourceID", "WEB")
                        .header("X-ClientLocalIP", "192.168.1.100")
                        .header("X-ClientPublicIP", "192.168.1.100")
                        .header("X-MACAddress", "02:00:00:00:00:00")
                        .header("X-PrivateKey", apiKey)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    List<Map<String, Object>> fetched = (List<Map<String, Object>>) data.get("fetched");
                    if (fetched != null) {
                        merged.addAll(fetched);
                    }
                }
            } catch (Exception e) {
                log.warn("Option quote batch failed ({} tokens): {}", chunk.size(), e.getMessage());
            }
        }
        return merged;
    }

    /**
     * Backs out a single per-strike IV (percent) from the live CE/PE premiums via the
     * Black-Scholes solver, averaging the two sides when both solve. Falls back to 12.5 if
     * the solver is unavailable or the premiums can't be inverted (strictly non-regressive).
     */
    private double solveStrikeIv(double spot, int strike, double years, double ceLtp, double peLtp) {
        if (blackScholesService == null) {
            return 12.5;
        }
        double ceIv = blackScholesService.impliedVol(spot, strike, years, true, ceLtp);
        double peIv = blackScholesService.impliedVol(spot, strike, years, false, peLtp);
        if (ceIv > 0 && peIv > 0) return Math.round((ceIv + peIv) / 2.0 * 100.0) / 100.0;
        if (ceIv > 0) return ceIv;
        if (peIv > 0) return peIv;
        return 12.5;
    }

    private static class OptionSnapshotDtoBuilder {
        int strike;
        long ceOi;
        long peOi;
        long ceOiChange;
        long peOiChange;
        double ceLtp;
        double peLtp;
        long ceVolume;
        long peVolume;

        OptionSnapshotDtoBuilder(int strike) {
            this.strike = strike;
        }
    }

    private String findCurrentMonthFutureSymbol() {
        LocalDate today = com.nifty.analysis.util.TimeUtil.todayIst();

        // Prefer scanning the scrip master for the nearest non-expired NIFTY future. This is
        // format-agnostic, so a wrong futures value (token never resolving) is avoided even if
        // Angel One's symbol format differs from the computed one.
        String best = null;
        LocalDate bestExpiry = null;
        for (ScripInfo info : scripMap.values()) {
            String sym = info.symbol();
            if (sym == null || !sym.startsWith("NIFTY") || !sym.endsWith("FUT")) {
                continue;
            }
            LocalDate exp = parseScripExpiry(info.expiry());
            if (exp != null && !exp.isBefore(today) && (bestExpiry == null || exp.isBefore(bestExpiry))) {
                bestExpiry = exp;
                best = sym;
            }
        }
        if (best != null) {
            return best;
        }

        // Fallback: compute the conventional last-expiry-day (Tuesday) monthly symbol.
        LocalDate lastExpiry = com.nifty.analysis.util.TimeUtil.lastMonthlyExpiry(today);
        if (today.isAfter(lastExpiry)) {
            lastExpiry = com.nifty.analysis.util.TimeUtil.lastMonthlyExpiry(today.plusMonths(1));
        }
        String formatted = lastExpiry.format(DateTimeFormatter.ofPattern("ddMMMyy").withLocale(Locale.ENGLISH)).toUpperCase();
        return "NIFTY" + formatted + "FUT";
    }

    /**
     * Layer 1 expiry source: the nearest NIFTY contract expiry on/after {@code from}, read from the
     * loaded scrip master. Empty until the master is downloaded (callers then fall back to the
     * weekday calendar). The exchange bakes holiday shifts into these dates, so they're authoritative.
     */
    @Override
    public java.util.Optional<LocalDate> nearestWeeklyExpiry(LocalDate from) {
        if (!scripMasterLoaded) {
            return java.util.Optional.empty();
        }
        java.util.List<String> expiries = new ArrayList<>();
        for (ScripInfo info : scripMap.values()) {
            if (info.expiry() != null && !info.expiry().isBlank()) {
                expiries.add(info.expiry());
            }
        }
        return nearestExpiryFrom(expiries, from, this::parseScripExpiry);
    }

    /** Pure: smallest parseable expiry >= from. Extracted for unit testing. */
    static java.util.Optional<LocalDate> nearestExpiryFrom(java.util.Collection<String> expiryStrings,
            LocalDate from, java.util.function.Function<String, LocalDate> parser) {
        LocalDate best = null;
        for (String s : expiryStrings) {
            LocalDate exp = parser.apply(s);
            if (exp != null && !exp.isBefore(from) && (best == null || exp.isBefore(best))) {
                best = exp;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** Parses an Angel One scrip-master expiry string (e.g. "26JUN2026") to a date; null if unparseable. */
    private LocalDate parseScripExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            return null;
        }
        for (String pattern : new String[]{"ddMMMyyyy", "ddMMMyy", "dd-MMM-yyyy"}) {
            try {
                // Case-INSENSITIVE: Angel sends uppercase months ("26JUN2026") but the locale's
                // short month is "Jun" — a case-sensitive parse would fail and silently disable Layer 1.
                DateTimeFormatter f = new java.time.format.DateTimeFormatterBuilder()
                        .parseCaseInsensitive()
                        .appendPattern(pattern)
                        .toFormatter(Locale.ENGLISH);
                return LocalDate.parse(expiry.trim(), f);
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private String findCurrentOptionExpiryDateSymbolStr() {
        LocalDate today = com.nifty.analysis.util.TimeUtil.todayIst();
        LocalDate expiry = com.nifty.analysis.util.TimeUtil.nextWeeklyExpiry(today);
        // On expiry day after market close, roll to the following week's expiry.
        if (today.getDayOfWeek() == com.nifty.analysis.util.TimeUtil.expiryDay()
                && com.nifty.analysis.util.TimeUtil.nowIst().getHour() >= 16) {
            expiry = com.nifty.analysis.util.TimeUtil.nextWeeklyExpiry(today.plusDays(1));
        }
        return expiry.format(DateTimeFormatter.ofPattern("ddMMMyy").withLocale(Locale.ENGLISH)).toUpperCase();
    }


    private double parseDouble(Object obj) {
        if (obj == null) return 0.0;
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private long parseLong(Object obj) {
        if (obj == null) return 0L;
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private MarketSnapshotDto getSimulatedFallbackMarketData() {
        dataFeedStatus.update(false); // degraded — every caller of this is on simulated data
        double spot = 23500.0 + (new Random().nextDouble() - 0.5) * 10.0;
        return new MarketSnapshotDto(spot, spot + 30.0, 13.5, 500000.0, com.nifty.analysis.util.TimeUtil.nowIst());
    }

    private List<OptionSnapshotDto> getSimulatedFallbackOptions() {
        List<OptionSnapshotDto> list = new ArrayList<>();
        double spot = 23500.0;
        int atmStrike = 23500;
        LocalDateTime now = com.nifty.analysis.util.TimeUtil.nowIst();
        for (int i = -20; i <= 20; i++) {
            int strike = atmStrike + (i * 50);
            list.add(new OptionSnapshotDto(strike, 1000000L, 1100000L, 5000L, 10000L, 12.5, 1.1, (double) atmStrike, 150000L, 160000L, now));
        }
        return list;
    }

    private static String getTOTPCode(String secretKey) {
        try {
            byte[] keyBytes = decodeBase32(secretKey);
            long timeWindow = System.currentTimeMillis() / 1000L / 30L;
            byte[] data = ByteBuffer.allocate(8).putLong(timeWindow).array();
            
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) |
                         ((hash[offset + 1] & 0xff) << 16) |
                         ((hash[offset + 2] & 0xff) << 8) |
                         (hash[offset + 3] & 0xff);
            
            int otp = binary % 1000000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate TOTP", e);
        }
    }

    private static byte[] decodeBase32(String base32) {
        String allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        base32 = base32.toUpperCase().replaceAll("[^" + allowed + "]", "");
        int len = base32.length();
        byte[] bytes = new byte[len * 5 / 8];
        int val = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < len; i++) {
            val = (val << 5) | allowed.indexOf(base32.charAt(i));
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes[index++] = (byte) ((val >> bits) & 0xFF);
            }
        }
        return bytes;
    }

    public String getJwtToken() {
        return this.jwtToken;
    }

    public String getFeedToken() {
        return this.feedToken;
    }

    public String getClientCode() {
        return this.clientCode;
    }

    public String getApiKey() {
        return this.apiKey;
    }

    /** Forces a login if not already authenticated (so feedToken/jwtToken are populated). */
    public void ensureSession() {
        ensureAuthenticated();
    }

    /** An instrument to stream, grouped by Angel exchangeType (1=NSE_CM index, 2=NSE_FO futures). */
    public record StreamInstrument(int exchangeType, String token, String kind) {}

    /** A streamable option contract (NFO) with its strike + type, for SnapQuote streaming. */
    public record OptionStreamInstrument(String token, int strike, String optionType) {}

    /** CE/PE tokens around ATM (±20 strikes) given the current spot, for live OI streaming. */
    public java.util.List<OptionStreamInstrument> getOptionStreamInstruments(double spot) {
        java.util.List<OptionStreamInstrument> list = new ArrayList<>();
        int atm = ((int) Math.round(spot / 50.0)) * 50;
        String expiry = findCurrentOptionExpiryDateSymbolStr();
        for (int i = -20; i <= 20; i++) {
            int strike = atm + i * 50;
            for (String type : new String[]{"CE", "PE"}) {
                String sym = "NIFTY" + expiry + strike + type;
                if (scripMap.containsKey(sym)) {
                    list.add(new OptionStreamInstrument(scripMap.get(sym).token(), strike, type));
                }
            }
        }
        return list;
    }

    /** Nifty spot, India VIX and the current-month future — the index feed for live ticks. */
    public java.util.List<StreamInstrument> getStreamInstruments() {
        java.util.List<StreamInstrument> list = new ArrayList<>();
        String spot = scripMap.containsKey("Nifty 50") ? scripMap.get("Nifty 50").token() : "99926000";
        String vix = scripMap.containsKey("INDIA VIX") ? scripMap.get("INDIA VIX").token() : "99926017";
        list.add(new StreamInstrument(1, spot, "SPOT"));
        list.add(new StreamInstrument(1, vix, "VIX"));
        String futSym = findCurrentMonthFutureSymbol();
        if (futSym != null && scripMap.containsKey(futSym)) {
            list.add(new StreamInstrument(2, scripMap.get(futSym).token(), "FUTURE"));
        }
        return list;
    }

    public String getExpiryDateSymbolStr() {
        return findCurrentOptionExpiryDateSymbolStr();
    }

    public record ScripTokenDetails(String token, String symbol, String exchSeg) {}

    public ScripTokenDetails getScripDetails(String symbol) {
        ScripInfo info = scripMap.get(symbol);
        if (info != null) {
            return new ScripTokenDetails(info.token(), info.symbol(), info.exchSeg());
        }
        return null;
    }

    public double fetchLtp(String exchange, String token) {
        ensureAuthenticated();
        if ("SIMULATED_JWT_TOKEN".equals(jwtToken)) {
            return -1.0; // sentinel: no live price (simulated/degraded session) — callers must NOT fabricate one
        }
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("mode", "LTP");
            Map<String, List<String>> exchangeTokens = new HashMap<>();
            exchangeTokens.put(exchange, Collections.singletonList(token));
            request.put("exchangeTokens", exchangeTokens);

            Map<String, Object> response = webClientBuilder.build().post()
                    .uri("https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/")
                    .header("Authorization", "Bearer " + jwtToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", "192.168.1.100")
                    .header("X-ClientPublicIP", "192.168.1.100")
                    .header("X-MACAddress", "02:00:00:00:00:00")
                    .header("X-PrivateKey", apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> fetched = (List<Map<String, Object>>) data.get("fetched");
                if (fetched != null && !fetched.isEmpty()) {
                    return parseDouble(fetched.get(0).get("ltp"));
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch LTP for exchange: {}, token: {}", exchange, token, e);
        }
        return -1.0; // sentinel: live LTP unavailable — callers must NOT fabricate a price
    }
}
