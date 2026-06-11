package com.nifty.analysis.service;

import com.nifty.analysis.agent.DecisionAgent;
import com.nifty.analysis.collector.client.MarketDataClient;
import com.nifty.analysis.collector.client.OptionChainClient;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.MarketCandle;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketCandleRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCollectorService {

    private final MarketDataClient marketDataClient;
    private final OptionChainClient optionChainClient;
    
    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final TradeSignalRepository tradeSignalRepository;
    private final TradeResultRepository tradeResultRepository;
    
    private final RedisService redisService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final OptionsIndicatorService optionsIndicatorService;
    private final DecisionAgent decisionAgent;
    private final TelegramBotService telegramBotService;
    private final LlmService llmService;

    @Transactional
    public void collect() {
        log.info("Starting market and option chain data collection cycle...");
        try {
            LocalDateTime now = LocalDateTime.now();
            
            // 1. Fetch & Store Market Snapshot
            MarketSnapshotDto marketData = marketDataClient.fetchMarketData();
            
            // Query previous snapshot to retrieve last calculated EMA values
            Optional<MarketSnapshot> prevSnapshot = marketSnapshotRepository.findLatest();
            Double prevEma20 = prevSnapshot.map(MarketSnapshot::getEma20).orElse(null);
            Double prevEma50 = prevSnapshot.map(MarketSnapshot::getEma50).orElse(null);
            
            // Calculate technical indicators
            double ema20 = technicalIndicatorService.calculateEma(marketData.niftySpot(), prevEma20, 20);
            double ema50 = technicalIndicatorService.calculateEma(marketData.niftySpot(), prevEma50, 50);
            double rsi = technicalIndicatorService.calculateRsi(marketData.niftySpot(), marketData.timestamp());
            double vwap = technicalIndicatorService.calculateVwap(marketData.niftySpot(), marketData.volume(), marketData.timestamp());
            
            MarketSnapshot snapshot = new MarketSnapshot();
            snapshot.setSnapshotTime(marketData.timestamp());
            snapshot.setNiftySpot(marketData.niftySpot());
            snapshot.setNiftyFuture(marketData.niftyFuture());
            snapshot.setIndiaVix(marketData.indiaVix());
            snapshot.setVolume(marketData.volume());
            snapshot.setEma20(ema20);
            snapshot.setEma50(ema50);
            snapshot.setRsi(rsi);
            snapshot.setVwap(vwap);
            
            // Persist & Cache Market Snapshot
            marketSnapshotRepository.save(snapshot);
            redisService.saveLatestMarketSnapshot(snapshot);
            log.info("Market Snapshot persisted: Spot={}, Future={}, VIX={}, EMA20={}, EMA50={}, RSI={}, VWAP={}", 
                    snapshot.getNiftySpot(), snapshot.getNiftyFuture(), snapshot.getIndiaVix(),
                    snapshot.getEma20(), snapshot.getEma50(), snapshot.getRsi(), snapshot.getVwap());

            // 2. Fetch & Store Option Chain Snapshots
            List<OptionSnapshotDto> optionChainData = optionChainClient.fetchOptionChain();
            
            // Calculate Max Pain for the current option chain
            double calculatedMaxPain = optionsIndicatorService.calculateMaxPain(optionChainData);
            
            List<OptionSnapshot> optionSnapshots = optionChainData.stream().map(dto -> {
                OptionSnapshot option = new OptionSnapshot();
                option.setSnapshotTime(dto.timestamp());
                option.setStrikePrice(dto.strikePrice());
                option.setCeOi(dto.ceOi());
                option.setPeOi(dto.peOi());
                option.setCeOiChange(dto.ceOiChange());
                option.setPeOiChange(dto.peOiChange());
                option.setIv(dto.iv());
                option.setPcr(dto.pcr());
                option.setMaxPain(calculatedMaxPain);
                option.setCeVolume(dto.ceVolume());
                option.setPeVolume(dto.peVolume());
                return option;
            }).toList();
            
            optionSnapshotRepository.saveAll(optionSnapshots);
            redisService.saveLatestOptionChain(optionSnapshots);
            log.info("Option Chain snapshot persisted ({} strikes, Max Pain={})", optionSnapshots.size(), calculatedMaxPain);

            // 3. Update Market Candles (5m, 15m, 30m, 60m Timeframes)
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "5m");
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "15m");
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "30m");
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "60m");

            // 4. Trigger Decision Agent signal evaluations
            decisionAgent.evaluateMarketForSignals(snapshot, prevSnapshot.map(MarketSnapshot::getNiftySpot).orElse(null));

            // 5. Update and resolve active trade signals in real-time
            updateActiveTrades(snapshot);

        } catch (Exception e) {
            log.error("Error during data collection cycle", e);
        }
    }

    private void updateCandle(double spot, double totalVolume, LocalDateTime now, String timeframe) {
        int minutes = 5;
        if ("15m".equals(timeframe)) {
            minutes = 15;
        } else if ("30m".equals(timeframe)) {
            minutes = 30;
        } else if ("60m".equals(timeframe)) {
            minutes = 60;
        }
        LocalDateTime candleStart = roundToTimeframe(now, minutes);
        
        // Find volume at the start of the timeframe
        double volumeBefore = 0.0;
        Optional<MarketSnapshot> prevSnap = marketSnapshotRepository.findLatestBefore(candleStart);
        if (prevSnap.isPresent()) {
            if (prevSnap.get().getSnapshotTime().toLocalDate().equals(candleStart.toLocalDate())) {
                volumeBefore = prevSnap.get().getVolume() != null ? prevSnap.get().getVolume() : 0.0;
            }
        }
        double currentCandleVolume = Math.max(0.0, totalVolume - volumeBefore);
        
        // Find existing candle for the timeframe and start time
        List<MarketCandle> existingCandles = marketCandleRepository.findLatestByTimeframe(timeframe, 1);
        
        MarketCandle candle;
        if (!existingCandles.isEmpty() && existingCandles.getFirst().getTimestamp().equals(candleStart)) {
            candle = existingCandles.getFirst();
            candle.setHigh(Math.max(candle.getHigh(), spot));
            candle.setLow(Math.min(candle.getLow(), spot));
            candle.setClose(spot);
            candle.setVolume(currentCandleVolume);
        } else {
            candle = new MarketCandle();
            candle.setTimestamp(candleStart);
            candle.setOpen(spot);
            candle.setHigh(spot);
            candle.setLow(spot);
            candle.setClose(spot);
            candle.setVolume(currentCandleVolume);
            candle.setTimeframe(timeframe);
        }
        
        marketCandleRepository.save(candle);
        log.debug("Candle ({}) updated: Timestamp={}, Open={}, Close={}, Volume={}", 
                timeframe, candle.getTimestamp(), candle.getOpen(), candle.getClose(), candle.getVolume());
    }

    private LocalDateTime roundToTimeframe(LocalDateTime time, int minutes) {
        int roundedMinute = (time.getMinute() / minutes) * minutes;
        return time.truncatedTo(ChronoUnit.HOURS)
                .plusMinutes(roundedMinute)
                .withSecond(0)
                .withNano(0);
    }

    @Transactional
    public void updateActiveTrades(MarketSnapshot latest) {
        log.info("Checking and updating active trades relative to spot price {}...", latest.getNiftySpot());
        List<TradeSignal> activeSignals = tradeSignalRepository.findByStatus("ACTIVE");
        if (activeSignals.isEmpty()) {
            return;
        }

        double spot = latest.getNiftySpot();
        for (TradeSignal signal : activeSignals) {
            Optional<MarketSnapshot> entrySnapshot = marketSnapshotRepository.findLatestBefore(signal.getSignalTime());
            double entrySpot = entrySnapshot.map(MarketSnapshot::getNiftySpot).orElse(spot);

            boolean isCall = "BUY_CE".equals(signal.getSignalType());
            double currentOptionPrice;
            if (isCall) {
                currentOptionPrice = signal.getEntry() + (spot - entrySpot) * 0.5;
            } else {
                currentOptionPrice = signal.getEntry() + (entrySpot - spot) * 0.5;
            }

            currentOptionPrice = Math.round(currentOptionPrice * 100.0) / 100.0;

            if (currentOptionPrice <= signal.getStopLoss()) {
                signal.setStatus("FAILED");
                tradeSignalRepository.save(signal);

                TradeResult result = new TradeResult();
                result.setSignal(signal);
                result.setOutcome("STOP_LOSS");
                result.setProfitLoss(signal.getStopLoss() - signal.getEntry());
                result.setHoldingTime(java.time.Duration.between(signal.getSignalTime(), latest.getSnapshotTime()).toSeconds());
                result.setAccuracy(0.0);
                tradeResultRepository.save(result);

                // Asynchronously trigger post-mortem reflection for the failed trade
                List<MarketSnapshot> context = marketSnapshotRepository.findBetween(signal.getSignalTime(), latest.getSnapshotTime());
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        llmService.generatePostMortem(signal, context);
                    } catch (Exception ex) {
                        log.error("Error executing post-mortem reflection task", ex);
                    }
                });

                String msg = String.format("🚨 *TRADE RESOLVED: STOP LOSS HIT*\n\n" +
                        "*Signal:* %s Strike %d\n" +
                        "*Entry Premium:* %.2f (Nifty Spot at Entry: %.2f)\n" +
                        "*Current Premium:* %.2f (Current Nifty Spot: %.2f)\n" +
                        "*Stop Loss:* %.2f\n" +
                        "*P&L:* %.2f points\n" +
                        "*Holding Time:* %d seconds",
                        signal.getSignalType(), signal.getStrike(), signal.getEntry(), entrySpot,
                        currentOptionPrice, spot, signal.getStopLoss(), result.getProfitLoss(), result.getHoldingTime());
                telegramBotService.sendMessage(msg);
                log.info("Active trade resolved: SL hit for signal ID={}", signal.getId());
            } else if (currentOptionPrice >= signal.getTarget2()) {
                signal.setStatus("COMPLETED");
                tradeSignalRepository.save(signal);

                TradeResult result = new TradeResult();
                result.setSignal(signal);
                result.setOutcome("TARGET2");
                result.setProfitLoss(signal.getTarget2() - signal.getEntry());
                result.setHoldingTime(java.time.Duration.between(signal.getSignalTime(), latest.getSnapshotTime()).toSeconds());
                result.setAccuracy(100.0);
                tradeResultRepository.save(result);

                String msg = String.format("🎉 *TRADE RESOLVED: TARGET 2 HIT*\n\n" +
                        "*Signal:* %s Strike %d\n" +
                        "*Entry Premium:* %.2f (Nifty Spot at Entry: %.2f)\n" +
                        "*Current Premium:* %.2f (Current Nifty Spot: %.2f)\n" +
                        "*Target 2:* %.2f\n" +
                        "*P&L:* +%.2f points\n" +
                        "*Holding Time:* %d seconds",
                        signal.getSignalType(), signal.getStrike(), signal.getEntry(), entrySpot,
                        currentOptionPrice, spot, signal.getTarget2(), result.getProfitLoss(), result.getHoldingTime());
                telegramBotService.sendMessage(msg);
                log.info("Active trade resolved: Target 2 hit for signal ID={}", signal.getId());
            }
        }
    }

    @Transactional(readOnly = true)
    public void send30MinSummary() {
        log.info("Generating and sending Nifty 30-minute status update...");
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDateTime todayStart = nowIst.toLocalDate().atStartOfDay();
        LocalDateTime todayEnd = nowIst.toLocalDate().atTime(23, 59, 59);

        List<MarketSnapshot> todaySnapshots = marketSnapshotRepository.findBetween(todayStart, todayEnd);
        if (todaySnapshots.isEmpty()) {
            Optional<MarketSnapshot> latestOpt = marketSnapshotRepository.findLatest();
            if (latestOpt.isEmpty()) {
                log.warn("No market snapshots found. Cannot send 30-min summary.");
                return;
            }
            todaySnapshots = List.of(latestOpt.get());
        }

        // Sort chronologically
        todaySnapshots = new java.util.ArrayList<>(todaySnapshots);
        todaySnapshots.sort(java.util.Comparator.comparing(MarketSnapshot::getSnapshotTime));

        MarketSnapshot latest = todaySnapshots.get(todaySnapshots.size() - 1);
        double open = todaySnapshots.get(0).getNiftySpot();
        double high = todaySnapshots.stream().mapToDouble(MarketSnapshot::getNiftySpot).max().orElse(latest.getNiftySpot());
        double low = todaySnapshots.stream().mapToDouble(MarketSnapshot::getNiftySpot).min().orElse(latest.getNiftySpot());

        List<TradeSignal> activeTrades = tradeSignalRepository.findByStatus("ACTIVE");
        List<TradeSignal> todayTrades = tradeSignalRepository.findBySignalTimeAfter(todayStart);
        List<TradeSignal> resolvedTodayTrades = todayTrades.stream()
                .filter(t -> !"ACTIVE".equals(t.getStatus()))
                .toList();

        String aiSummary = llmService.generateMarketSummary(
                latest.getNiftySpot(), open, high, low,
                latest.getRsi(), latest.getVwap(), latest.getEma20(), latest.getEma50(),
                activeTrades, resolvedTodayTrades
        );

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *NIFTY 30-MINUTE MARKET UPDATE* 📊\n\n");
        sb.append("💰 *Nifty Spot Prices:*\n");
        sb.append(String.format("• *Open:* `%.2f`\n", open));
        sb.append(String.format("• *High:* `%.2f`\n", high));
        sb.append(String.format("• *Low:* `%.2f`\n", low));
        sb.append(String.format("• *Current (Running):* `%.2f`\n\n", latest.getNiftySpot()));

        sb.append("📈 *Technical Indicators:*\n");
        sb.append(String.format("• *RSI (14):* `%.2f`\n", latest.getRsi() != null ? latest.getRsi() : 50.0));
        sb.append(String.format("• *VWAP:* `%.2f`\n", latest.getVwap() != null ? latest.getVwap() : latest.getNiftySpot()));
        sb.append(String.format("• *EMA 20:* `%.2f` | *EMA 50:* `%.2f`\n\n",
                latest.getEma20() != null ? latest.getEma20() : latest.getNiftySpot(),
                latest.getEma50() != null ? latest.getEma50() : latest.getNiftySpot()));

        sb.append("⚡ *Trade Signal Status:*\n");
        sb.append("• *Active/Running Trades:*\n");
        if (activeTrades.isEmpty()) {
            sb.append("  _None_\n");
        } else {
            for (TradeSignal t : activeTrades) {
                Optional<MarketSnapshot> entrySnapshot = marketSnapshotRepository.findLatestBefore(t.getSignalTime());
                double entrySpot = entrySnapshot.map(MarketSnapshot::getNiftySpot).orElse(latest.getNiftySpot());
                boolean isCall = "BUY_CE".equals(t.getSignalType());
                double currentOptionPrice = isCall ? t.getEntry() + (latest.getNiftySpot() - entrySpot) * 0.5
                                                   : t.getEntry() + (entrySpot - latest.getNiftySpot()) * 0.5;
                currentOptionPrice = Math.round(currentOptionPrice * 100.0) / 100.0;

                sb.append(String.format("  - %s Strike %d (Entry: %.2f, Current: %.2f, SL: %.2f, Target2: %.2f)\n",
                        t.getSignalType(), t.getStrike(), t.getEntry(), currentOptionPrice, t.getStopLoss(), t.getTarget2()));
            }
        }

        sb.append("• *Today's Resolved Trades:*\n");
        if (resolvedTodayTrades.isEmpty()) {
            sb.append("  _None_\n\n");
        } else {
            for (TradeSignal t : resolvedTodayTrades) {
                Optional<TradeResult> resOpt = tradeResultRepository.findBySignalId(t.getId());
                String outcome = resOpt.map(TradeResult::getOutcome).orElse(t.getStatus());
                double pnl = resOpt.map(TradeResult::getProfitLoss).orElse(0.0);
                sb.append(String.format("  - %s Strike %d: *%s* (P&L: %s%.2f points)\n",
                        t.getSignalType(), t.getStrike(), outcome, pnl >= 0 ? "+" : "", pnl));
            }
            sb.append("\n");
        }

        sb.append("🤖 *AI Market Analysis:*\n");
        sb.append(aiSummary).append("\n");

        telegramBotService.sendMessage(sb.toString());
    }
}
