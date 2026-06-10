package com.nifty.analysis;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.engine.ConfidenceEngine;
import com.nifty.analysis.agent.CriticAgent;
import com.nifty.analysis.agent.MarketRegimeAgent;
import com.nifty.analysis.agent.MultiTimeframeAgent;
import com.nifty.analysis.agent.OptionsAgent;
import com.nifty.analysis.agent.SentimentAgent;
import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.agent.EventRiskAgent;
import com.nifty.analysis.service.OptionsIndicatorService;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LiveConfidenceRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Live Market Confidence Evaluator ===");

        // 1. Parse credentials from application.yml
        String apiKey = "";
        String clientCode = "";
        String password = "";
        String totpKey = "";

        try (BufferedReader br = new BufferedReader(new FileReader("src/main/resources/application.yml"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("api-key:"))
                    apiKey = extractValue(line);
                else if (line.startsWith("client-code:"))
                    clientCode = extractValue(line);
                else if (line.startsWith("password:"))
                    password = extractValue(line);
                else if (line.startsWith("totp-key:"))
                    totpKey = extractValue(line);
            }
        }

        if (apiKey.isEmpty() || clientCode.isEmpty() || password.isEmpty() || totpKey.isEmpty()) {
            System.err.println("Error: Angel One credentials could not be parsed from application.yml!");
            return;
        }

        // 2. Initialize Client
        WebClient.Builder webClientBuilder = WebClient.builder();
        AngelOneDataClient client = new AngelOneDataClient(webClientBuilder);
        setField(client, "apiKey", apiKey);
        setField(client, "clientCode", clientCode);
        setField(client, "password", password);
        setField(client, "totpKey", totpKey);

        System.out.println("Initializing client & downloading scrip master...");
        client.init();
        for (int i = 0; i < 8; i++) {
            Thread.sleep(1000);
        }

        // 3. Fetch Live Market Data & Options Chain
        System.out.println("Fetching Live Market Data...");
        MarketSnapshotDto marketData = client.fetchMarketData();
        System.out.println("Fetching Live Option Chain...");
        List<OptionSnapshotDto> optionChain = client.fetchOptionChain();

        System.out.println("\n--- Live Market Data ---");
        System.out.println("Spot Price: " + marketData.niftySpot());
        System.out.println("Future Price: " + marketData.niftyFuture());
        System.out.println("VIX: " + marketData.indiaVix());
        System.out.println("Volume: " + marketData.volume());
        System.out.println("Timestamp: " + marketData.timestamp());

        // 4. Create MarketSnapshot and OptionSnapshot entities for the calculations
        MarketSnapshot snapshot = new MarketSnapshot();
        snapshot.setSnapshotTime(marketData.timestamp());
        snapshot.setNiftySpot(marketData.niftySpot());
        snapshot.setNiftyFuture(marketData.niftyFuture());
        snapshot.setIndiaVix(marketData.indiaVix());
        snapshot.setVolume(marketData.volume());
        // Assume recent EMA and RSI from user's latest logs as the current situation
        // reference
        snapshot.setEma20(23162.66);
        snapshot.setEma50(23174.87);
        snapshot.setRsi(44.39);
        snapshot.setVwap(23158.30); // from log reference

        List<OptionSnapshot> optionEntities = new ArrayList<>();
        double calculatedMaxPain = new OptionsIndicatorService().calculateMaxPain(optionChain);
        for (OptionSnapshotDto dto : optionChain) {
            OptionSnapshot entity = new OptionSnapshot();
            entity.setSnapshotTime(dto.timestamp());
            entity.setStrikePrice(dto.strikePrice());
            entity.setCeOi(dto.ceOi());
            entity.setPeOi(dto.peOi());
            entity.setCeOiChange(dto.ceOiChange());
            entity.setPeOiChange(dto.peOiChange());
            entity.setIv(dto.iv());
            entity.setPcr(dto.pcr());
            entity.setMaxPain(calculatedMaxPain);
            entity.setCeVolume(dto.ceVolume());
            entity.setPeVolume(dto.peVolume());
            optionEntities.add(entity);
        }

        // 5. Evaluate indicators using the real business logic
        var mockSnapshotRepo = org.mockito.Mockito.mock(com.nifty.analysis.repository.MarketSnapshotRepository.class);
        var technicalIndicatorService = new com.nifty.analysis.service.TechnicalIndicatorService(mockSnapshotRepo);
        TechnicalAgent technicalAgent = new TechnicalAgent(technicalIndicatorService);
        AgentResponse techResponse = technicalAgent.analyze(snapshot);
        System.out.println("\n--- Technical Agent Bias ---");
        System.out.println("Bias: " + techResponse.bias() + " (Score: " + techResponse.score() + ")");
        System.out.println("Comments: " + techResponse.comments());

        boolean isCall = !"BEARISH".equals(techResponse.bias());

        // Mock event risk as Neutral (no scheduled event in this test run)
        EventRiskAgent eventRiskAgent = new EventRiskAgent() {
            @Override
            public AgentResponse evaluateCurrentRisk(java.time.LocalDate date) {
                return new AgentResponse(0.0, "NEUTRAL", List.of("No major macroeconomic events scheduled today"));
            }
        };

        CriticAgent criticAgent = new CriticAgent(eventRiskAgent);
        OptionsIndicatorService optionsIndicatorService = new OptionsIndicatorService();

        // Instantiate confidence engine dependencies (mock/stub what relies on DB
        // weights)
        var mockWeightsRepo = org.mockito.Mockito.mock(com.nifty.analysis.repository.ConfidenceWeightRepository.class);
        org.mockito.Mockito.when(mockWeightsRepo.findByActiveTrue()).thenReturn(List.of()); // default weights will be
                                                                                            // used

        // Mock MarketRegimeAgent since it depends on snapshot database history
        MarketRegimeAgent regimeAgent = new MarketRegimeAgent(null, null) {
            @Override
            public AgentResponse analyze(LocalDateTime time) {
                // Return Bearish trending regime based on current Spot < EMA20 < EMA50
                return new AgentResponse(15.0, "TRENDING_BEARISH",
                        List.of("Trending Bearish regime (Spot < EMA20 < EMA50)"));
            }
        };

        com.nifty.analysis.repository.OptionSnapshotRepository optionSnapshotRepository = org.mockito.Mockito.mock(com.nifty.analysis.repository.OptionSnapshotRepository.class);
        OptionsAgent optionsAgent = new OptionsAgent(optionsIndicatorService, optionSnapshotRepository);
        com.nifty.analysis.collector.client.impl.YahooFinanceClient yahooFinanceClient = new com.nifty.analysis.collector.client.impl.YahooFinanceClient(
                webClientBuilder);
        SentimentAgent sentimentAgent = new SentimentAgent(yahooFinanceClient);
        // Set default sentiment values (will be read from @Value properties)
        setField(sentimentAgent, "giftNiftyPremium", 15.0);
        setField(sentimentAgent, "dowFuturesPoints", 80.0);
        setField(sentimentAgent, "dollarIndex", 101.5);
        setField(sentimentAgent, "crudeOil", 78.0);
        setField(sentimentAgent, "fiiNetCr", 450.0);

        MultiTimeframeAgent timeframeAgent = new MultiTimeframeAgent(null) {
            @Override
            public AgentResponse analyze(LocalDateTime time) {
                return new AgentResponse(50.0, "NEUTRAL", List.of("Live Confidence Runner timeframe neutral fallback"));
            }
        };

        ConfidenceEngine engine = new ConfidenceEngine(
                mockWeightsRepo,
                regimeAgent,
                technicalAgent,
                optionsAgent,
                sentimentAgent,
                optionsIndicatorService,
                timeframeAgent);

        // Calculate raw confidence score (spotChange assumed as -1.35 from recent
        // movements)
        double spotChange = -1.35;
        ConfidenceEngine.RawConfidenceResult rawResult = engine.calculateRawConfidence(snapshot, optionChain,
                spotChange, isCall);

        System.out.println("\n--- Raw Confidence Factors ---");
        rawResult.factorScores().forEach((factor, score) -> {
            System.out.println("  " + factor + ": " + score);
        });
        System.out.println("Overall Raw Confidence Score: " + rawResult.rawConfidence() + "%");

        // Critic penalties
        CriticAgent.CriticResult criticResult = criticAgent.evaluateAndApplyPenalties(
                rawResult.rawConfidence(), snapshot, optionEntities, isCall);

        System.out.println("\n--- Critic Penalties ---");
        if (criticResult.appliedPenalties().isEmpty()) {
            System.out.println("  None");
        } else {
            criticResult.appliedPenalties().forEach(p -> {
                System.out.println("  " + p.factor() + ": Adjust=" + p.scoreAdjustment() + " (" + p.comment() + ")");
            });
        }
        System.out.println("Final Adjusted Confidence Score: " + criticResult.adjustedConfidence() + "%");

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
