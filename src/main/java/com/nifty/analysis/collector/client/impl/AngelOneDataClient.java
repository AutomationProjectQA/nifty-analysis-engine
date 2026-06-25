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
public class AngelOneDataClient implements MarketDataClient, OptionChainClient {

    @Value("${nifty.angelone.api-key:}")
    private String apiKey;

    @Value("${nifty.angelone.client-code:}")
    private String clientCode;

    @Value("${nifty.angelone.password:}")
    private String password;

    @Value("${nifty.angelone.totp-key:}")
    private String totpKey;

    private final WebClient.Builder webClientBuilder;
    
    private String jwtToken;
    private String feedToken; // required by the streaming WebSocket (SmartWebSocketV2)
    private final Map<String, ScripInfo> scripMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastCeOiMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastPeOiMap = new ConcurrentHashMap<>();
    private boolean scripMasterLoaded = false;

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
        if (jwtToken != null) {
            return;
        }
        
        if (apiKey.isEmpty() || clientCode.isEmpty() || password.isEmpty() || totpKey.isEmpty()) {
            log.warn("Angel One SmartAPI credentials are not fully configured. Using simulated fallback token.");
            jwtToken = "SIMULATED_JWT_TOKEN";
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
    }

    @Override
    public MarketSnapshotDto fetchMarketData() {
        ensureAuthenticated();
        
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

                return new MarketSnapshotDto(spot, futures, vix, volume, LocalDateTime.now());
            }
        } catch (Exception e) {
            log.error("Failed to fetch market data from Angel One API. Using simulated fallback.", e);
        }
        return getSimulatedFallbackMarketData();
    }

    @Override
    public List<OptionSnapshotDto> fetchOptionChain() {
        ensureAuthenticated();
        
        if ("SIMULATED_JWT_TOKEN".equals(jwtToken)) {
            return getSimulatedFallbackOptions();
        }

        try {
            double spot = fetchMarketData().niftySpot();
            int atmStrike = ((int) Math.round(spot / 50.0)) * 50;

            String expiryDateSymbolStr = findCurrentOptionExpiryDateSymbolStr();
            List<String> optionSymbols = new ArrayList<>();
            Map<String, Integer> strikeSymbolMap = new HashMap<>();

            for (int i = -10; i <= 10; i++) {
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

            Map<String, Object> request = new HashMap<>();
            request.put("mode", "FULL");
            Map<String, List<String>> exchangeTokens = new HashMap<>();
            exchangeTokens.put("NFO", tokens);
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

                    if (isCe) {
                        builder.ceOi = oi;
                        long lastCeOi = lastCeOiMap.getOrDefault(strike, 0L);
                        builder.ceOiChange = lastCeOi > 0 ? (oi - lastCeOi) : 0L;
                        lastCeOiMap.put(strike, oi);
                        builder.ceIv = 12.5;
                        builder.ceVolume = volume;
                    } else {
                        builder.peOi = oi;
                        long lastPeOi = lastPeOiMap.getOrDefault(strike, 0L);
                        builder.peOiChange = lastPeOi > 0 ? (oi - lastPeOi) : 0L;
                        lastPeOiMap.put(strike, oi);
                        builder.peIv = 12.5;
                        builder.peVolume = volume;
                    }
                }

                List<OptionSnapshotDto> snapshots = new ArrayList<>();
                LocalDateTime now = LocalDateTime.now();
                for (OptionSnapshotDtoBuilder b : builders.values()) {
                    double pcr = b.ceOi > 0 ? (double) b.peOi / b.ceOi : 0.0;
                    snapshots.add(new OptionSnapshotDto(
                        b.strike,
                        b.ceOi,
                        b.peOi,
                        b.ceOiChange,
                        b.peOiChange,
                        12.5,
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

    private static class OptionSnapshotDtoBuilder {
        int strike;
        long ceOi;
        long peOi;
        long ceOiChange;
        long peOiChange;
        double ceIv;
        double peIv;
        long ceVolume;
        long peVolume;

        OptionSnapshotDtoBuilder(int strike) {
            this.strike = strike;
        }
    }

    private String findCurrentMonthFutureSymbol() {
        LocalDate today = LocalDate.now();
        LocalDate lastThursday = findLastThursdayOfMonth(today);
        if (today.isAfter(lastThursday)) {
            lastThursday = findLastThursdayOfMonth(today.plusMonths(1));
        }
        String formatted = lastThursday.format(DateTimeFormatter.ofPattern("ddMMMyy").withLocale(Locale.ENGLISH)).toUpperCase();
        return "NIFTY" + formatted + "FUT";
    }

    private String findCurrentOptionExpiryDateSymbolStr() {
        LocalDate today = LocalDate.now();
        LocalDate nextThursday = findNextThursday(today);
        if (today.getDayOfWeek() == java.time.DayOfWeek.THURSDAY && LocalDateTime.now().getHour() >= 16) {
            nextThursday = findNextThursday(today.plusDays(1));
        }
        return nextThursday.format(DateTimeFormatter.ofPattern("ddMMMyy").withLocale(Locale.ENGLISH)).toUpperCase();
    }

    private LocalDate findLastThursdayOfMonth(LocalDate date) {
        LocalDate lastDay = date.withDayOfMonth(date.lengthOfMonth());
        while (lastDay.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            lastDay = lastDay.minusDays(1);
        }
        return lastDay;
    }

    private LocalDate findNextThursday(LocalDate date) {
        LocalDate current = date;
        while (current.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            current = current.plusDays(1);
        }
        return current;
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
        double spot = 23500.0 + (new Random().nextDouble() - 0.5) * 10.0;
        return new MarketSnapshotDto(spot, spot + 30.0, 13.5, 500000.0, LocalDateTime.now());
    }

    private List<OptionSnapshotDto> getSimulatedFallbackOptions() {
        List<OptionSnapshotDto> list = new ArrayList<>();
        double spot = 23500.0;
        int atmStrike = 23500;
        LocalDateTime now = LocalDateTime.now();
        for (int i = -10; i <= 10; i++) {
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

    /** CE/PE tokens around ATM (±10 strikes) given the current spot, for live OI streaming. */
    public java.util.List<OptionStreamInstrument> getOptionStreamInstruments(double spot) {
        java.util.List<OptionStreamInstrument> list = new ArrayList<>();
        int atm = ((int) Math.round(spot / 50.0)) * 50;
        String expiry = findCurrentOptionExpiryDateSymbolStr();
        for (int i = -10; i <= 10; i++) {
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
            return 150.0;
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
        return 150.0;
    }
}
