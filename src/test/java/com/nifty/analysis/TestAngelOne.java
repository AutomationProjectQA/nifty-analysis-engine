package com.nifty.analysis;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.Map;

public class TestAngelOne {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Angel One SmartAPI Connectivity Verification ===");
        
        // 1. Parse credentials from application.yml
        String apiKey = "";
        String clientCode = "";
        String password = "";
        String totpKey = "";
        
        try (BufferedReader br = new BufferedReader(new FileReader("src/main/resources/application.yml"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("api-key:")) apiKey = extractValue(line);
                else if (line.startsWith("client-code:")) clientCode = extractValue(line);
                else if (line.startsWith("password:")) password = extractValue(line);
                else if (line.startsWith("totp-key:")) totpKey = extractValue(line);
            }
        }
        
        System.out.println("Parsed Client Code: " + clientCode);
        System.out.println("Parsed API Key: " + apiKey);
        System.out.println("Parsed Password: " + (password.isEmpty() ? "MISSING" : "****"));
        System.out.println("Parsed TOTP Key: " + (totpKey.isEmpty() ? "MISSING" : "****"));
        
        if (apiKey.isEmpty() || clientCode.isEmpty() || password.isEmpty() || totpKey.isEmpty()) {
            System.err.println("Error: Credentials could not be parsed from application.yml!");
            return;
        }

        // 2. Instantiate client and set fields via reflection
        WebClient.Builder webClientBuilder = WebClient.builder();
        AngelOneDataClient client = new AngelOneDataClient(webClientBuilder, new com.nifty.analysis.service.DataFeedStatus());
        
        setField(client, "apiKey", apiKey);
        setField(client, "clientCode", clientCode);
        setField(client, "password", password);
        setField(client, "totpKey", totpKey);

        // 3. Trigger scrip master loading and wait for it to download
        System.out.println("Initializing client & downloading scrip master...");
        client.init();
        
        // Wait up to 10 seconds for the scrip master background thread to complete
        System.out.print("Downloading scrip master");
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            System.out.print(".");
        }
        System.out.println(" Done!");

        // Print sample keys from scripMap to inspect option symbols
        Field mapField = client.getClass().getDeclaredField("scripMap");
        mapField.setAccessible(true);
        Map<String, ?> scripMap = (Map<String, ?>) mapField.get(client);
        System.out.println("\nTotal scripMap size: " + scripMap.size());

        // Get calculated symbol dates
        java.time.LocalDate today = java.time.LocalDate.now();
        System.out.println("Today's Date: " + today);
        
        // Find next Tuesday
        java.time.LocalDate current = today;
        while (current.getDayOfWeek() != java.time.DayOfWeek.TUESDAY) {
            current = current.plusDays(1);
        }
        System.out.println("Calculated Next Tuesday: " + current);
        String calculatedOptionDateStr = current.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy").withLocale(java.util.Locale.ENGLISH)).toUpperCase();
        System.out.println("Calculated Option Date Symbol String: " + calculatedOptionDateStr);

        // Print unique expiries of NIFTY options in scripMap
        java.util.Set<String> expiries = new java.util.TreeSet<>();
        for (Object info : scripMap.values()) {
            try {
                Field expiryField = info.getClass().getDeclaredField("expiry");
                expiryField.setAccessible(true);
                String exp = (String) expiryField.get(info);
                Field symbolField = info.getClass().getDeclaredField("symbol");
                symbolField.setAccessible(true);
                String sym = (String) symbolField.get(info);
                if (sym.startsWith("NIFTY") && exp != null && !exp.isEmpty()) {
                    expiries.add(exp);
                }
            } catch (Exception e) {}
        }
        System.out.println("Unique Expiry Dates in scripMap for NIFTY:");
        expiries.forEach(e -> System.out.println("  " + e));

        // Print Nifty keys that don't end with CE or PE (like Futures)
        System.out.println("\nNIFTY keys not ending with CE or PE (potential Futures):");
        scripMap.keySet().stream()
                .filter(k -> k.startsWith("NIFTY") && !k.endsWith("CE") && !k.endsWith("PE"))
                .limit(20)
                .forEach(k -> System.out.println("  " + k));

        // Find next Tuesday
        java.time.LocalDate nextTuesday = today;
        while (nextTuesday.getDayOfWeek() != java.time.DayOfWeek.TUESDAY) {
            nextTuesday = nextTuesday.plusDays(1);
        }
        String calculatedTuesdayStr = nextTuesday.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy").withLocale(java.util.Locale.ENGLISH)).toUpperCase();
        System.out.println("\nCalculated Tuesday Expiry: " + calculatedTuesdayStr);
        System.out.println("Matching keys for Tuesday option:");
        scripMap.keySet().stream()
                .filter(k -> k.startsWith("NIFTY" + calculatedTuesdayStr))
                .limit(5)
                .forEach(k -> System.out.println("  " + k));

        // 4. Fetch Market Snapshot
        System.out.println("\n--- Testing fetchMarketData() ---");
        try {
            var marketData = client.fetchMarketData();
            System.out.println("Market Data Result:");
            System.out.println("  Spot: " + marketData.niftySpot());
            System.out.println("  Future: " + marketData.niftyFuture());
            System.out.println("  VIX: " + marketData.indiaVix());
            System.out.println("  Volume: " + marketData.volume());
            System.out.println("  Timestamp: " + marketData.timestamp());
        } catch (Exception e) {
            System.err.println("Failed to fetch market data:");
            e.printStackTrace();
        }

        // 5. Fetch Option Chain
        System.out.println("\n--- Testing fetchOptionChain() ---");
        try {
            var options = client.fetchOptionChain();
            System.out.println("Option Chain Result (Total strikes returned: " + options.size() + "):");
            if (!options.isEmpty()) {
                // Print ATM and nearby strikes
                options.stream()
                       .limit(5)
                       .forEach(o -> System.out.println("  Strike: " + o.strikePrice() + 
                               " | CE OI: " + o.ceOi() + " | PE OI: " + o.peOi() + 
                               " | PCR: " + o.pcr()));
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch option chain:");
            e.printStackTrace();
        }
        
        System.out.println("\n=== Verification Complete ===");
        System.exit(0);
    }

    private static String extractValue(String line) {
        String val = line.substring(line.indexOf(":") + 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return val;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
