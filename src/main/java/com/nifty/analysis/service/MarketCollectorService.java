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
    private final OptionPricingService optionPricingService;
    private final OptionPremiumService optionPremiumService;
    private final OptionCostService optionCostService;
    private final DecisionAgent decisionAgent;
    private final TelegramBotService telegramBotService;
    private final LlmService llmService;
    private final MarketStreamPublisher marketStreamPublisher;
    private final com.nifty.analysis.engine.ConfidenceWeightTuner confidenceWeightTuner;

    @org.springframework.beans.factory.annotation.Value("${nifty.order-execution.lot-size:65}")
    private int lotSize;

    // NOT @Transactional on purpose: this cycle interleaves blocking external HTTP (Angel
    // One, LLM, Telegram, order placement) with DB writes. Wrapping it all in one
    // transaction would pin a DB connection across those slow calls (pool exhaustion).
    // Each persistence step commits independently; the scheduler guard ensures the cycle
    // is never re-entered concurrently.
    public void collect() {
        log.info("Starting market and option chain data collection cycle...");
        try {
            LocalDateTime now = com.nifty.analysis.util.TimeUtil.nowIst();

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
            double vwap = technicalIndicatorService.calculateVwap(marketData.niftySpot(), marketData.volume(),
                    marketData.timestamp());

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
            marketStreamPublisher.publishMarket(snapshot); // live push to the portal
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
            marketStreamPublisher.publishOptions(optionSnapshots); // live push to the portal
            log.info("Option Chain snapshot persisted ({} strikes, Max Pain={})", optionSnapshots.size(),
                    calculatedMaxPain);

            // 3. Update Market Candles (5m, 15m, 30m, 60m Timeframes)
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "5m");
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "15m");
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "30m");
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "60m");

            // 4. Trigger Decision Agent signal evaluations
            decisionAgent.evaluateMarketForSignals(snapshot,
                    prevSnapshot.map(MarketSnapshot::getNiftySpot).orElse(null));

            // 5. Update and resolve active trade signals in real-time
            updateActiveTrades(snapshot);

            // 6. Push the current signal set to the portal
            marketStreamPublisher.publishSignals(tradeSignalRepository.findAllByOrderBySignalTimeDesc());

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

    /** True once today (IST) is past the weekly expiry (Thursday on/after) of the signal's date. */
    private boolean isPastExpiry(LocalDateTime signalTime) {
        if (signalTime == null) {
            return false;
        }
        java.time.LocalDate expiry = signalTime.toLocalDate();
        while (expiry.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            expiry = expiry.plusDays(1);
        }
        return com.nifty.analysis.util.TimeUtil.todayIst().isAfter(expiry);
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

        // Theoretical premiums (Black-Scholes from live IV, with real delta + theta) — used
        // ONLY as a fallback when the live LTP is unavailable. Far more accurate than the old
        // flat-0.5-delta proxy. Built once per cycle; failure leaves the map empty (safe).
        java.util.Map<Integer, com.nifty.analysis.dto.OptionPremiumDto.StrikePremium> theoByStrike = new java.util.HashMap<>();
        try {
            for (com.nifty.analysis.dto.OptionPremiumDto.StrikePremium p : optionPremiumService.latestPremiums().premiums()) {
                theoByStrike.put(p.strike(), p);
            }
        } catch (Exception e) {
            log.warn("Could not compute theoretical premiums for trade resolution: {}", e.getMessage());
        }

        double spot = latest.getNiftySpot();
        for (TradeSignal signal : activeSignals) {
            // Prefer the entry spot stored on the signal; reconstruct only for legacy rows.
            double entrySpot = signal.getEntrySpot() != null ? signal.getEntrySpot()
                    : marketSnapshotRepository.findLatestBefore(signal.getSignalTime())
                            .map(MarketSnapshot::getNiftySpot).orElse(spot);

            boolean isCall = "BUY_CE".equals(signal.getSignalType());

            // 1. Prefer the real LIVE option premium (reflects the true market delta).
            double currentOptionPrice = optionPricingService.getOptionLtp(signal.getSignalType(), signal.getStrike());

            // 2. Fallback: Black-Scholes theoretical premium (real delta + theta), NOT a flat 0.5 proxy.
            if (currentOptionPrice <= 0) {
                com.nifty.analysis.dto.OptionPremiumDto.StrikePremium theo = theoByStrike.get(signal.getStrike());
                if (theo != null) {
                    currentOptionPrice = isCall ? theo.cePremium() : theo.pePremium();
                }
            }

            // 3. If neither a live nor a theoretical price is available, do NOT resolve on a
            // fabricated price — leave the trade ACTIVE and re-check next cycle.
            if (currentOptionPrice <= 0) {
                log.warn("No live or theoretical premium for signal id={} ({} {}). Leaving ACTIVE this cycle.",
                        signal.getId(), signal.getSignalType(), signal.getStrike());
                continue;
            }
            currentOptionPrice = Math.round(currentOptionPrice * 100.0) / 100.0;

            int quantity = signal.getQuantity() != null ? signal.getQuantity() : lotSize;

            // EOD/expiry square-off: if the option has passed its weekly expiry without hitting
            // SL or target, close it at its residual value instead of leaving it ACTIVE forever.
            if (isPastExpiry(signal.getSignalTime())) {
                double expGross = (currentOptionPrice - signal.getEntry()) * quantity;
                double expCost = optionCostService.roundTripCost(signal.getEntry(), currentOptionPrice, quantity);
                double expNet = Math.round((expGross - expCost) * 100.0) / 100.0;
                signal.setStatus("EXPIRED");
                tradeSignalRepository.save(signal);

                TradeResult result = new TradeResult();
                result.setSignal(signal);
                result.setOutcome("EXPIRED");
                result.setProfitLoss(expNet);
                result.setHoldingTime(java.time.Duration.between(signal.getSignalTime(), latest.getSnapshotTime()).toSeconds());
                result.setAccuracy(expNet >= 0 ? 100.0 : 0.0);
                tradeResultRepository.save(result);
                confidenceWeightTuner.reinforce(signal, expNet >= 0);

                telegramBotService.sendMessage(String.format(
                        "⌛ *TRADE RESOLVED: EXPIRED*\n\n*Signal:* %s Strike %d\n*Entry:* %.2f  *Exit (residual):* %.2f\n*Net P&L:* %.2f INR",
                        signal.getSignalType(), signal.getStrike(), signal.getEntry(), currentOptionPrice, expNet));
                log.info("Active trade resolved: EXPIRED for signal ID={}", signal.getId());
                continue;
            }

            if (currentOptionPrice <= signal.getStopLoss()) {
                signal.setStatus("FAILED");
                tradeSignalRepository.save(signal);

                double slGross = (signal.getStopLoss() - signal.getEntry()) * quantity;
                double slCost = optionCostService.roundTripCost(signal.getEntry(), signal.getStopLoss(), quantity);
                double slNet = Math.round((slGross - slCost) * 100.0) / 100.0;

                TradeResult result = new TradeResult();
                result.setSignal(signal);
                result.setOutcome("STOP_LOSS");
                result.setProfitLoss(slNet); // NET of round-trip costs (INR)
                result.setHoldingTime(
                        java.time.Duration.between(signal.getSignalTime(), latest.getSnapshotTime()).toSeconds());
                result.setAccuracy(0.0);
                tradeResultRepository.save(result);

                // Learn from the loss: discount factors that were confident yet wrong.
                confidenceWeightTuner.reinforce(signal, false);

                // Asynchronously trigger post-mortem reflection for the failed trade
                List<MarketSnapshot> context = marketSnapshotRepository.findBetween(signal.getSignalTime(),
                        latest.getSnapshotTime());
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
                        "*Gross P&L:* %.2f INR  |  *Costs:* %.2f INR\n" +
                        "*Net P&L:* %.2f INR\n" +
                        "*Holding Time:* %d seconds",
                        signal.getSignalType(), signal.getStrike(), signal.getEntry(), entrySpot,
                        currentOptionPrice, spot, signal.getStopLoss(), slGross, slCost, slNet,
                        result.getHoldingTime());
                telegramBotService.sendMessage(msg);
                log.info("Active trade resolved: SL hit for signal ID={}", signal.getId());
            } else if (currentOptionPrice >= signal.getTarget2()) {
                signal.setStatus("COMPLETED");
                tradeSignalRepository.save(signal);

                double tgtGross = (signal.getTarget2() - signal.getEntry()) * quantity;
                double tgtCost = optionCostService.roundTripCost(signal.getEntry(), signal.getTarget2(), quantity);
                double tgtNet = Math.round((tgtGross - tgtCost) * 100.0) / 100.0;

                TradeResult result = new TradeResult();
                result.setSignal(signal);
                result.setOutcome("TARGET2");
                result.setProfitLoss(tgtNet); // NET of round-trip costs (INR)
                result.setHoldingTime(
                        java.time.Duration.between(signal.getSignalTime(), latest.getSnapshotTime()).toSeconds());
                result.setAccuracy(100.0);
                tradeResultRepository.save(result);

                // Learn from the win: reinforce the factors that were confident and right.
                confidenceWeightTuner.reinforce(signal, true);

                String msg = String.format("🎉 *TRADE RESOLVED: TARGET 2 HIT*\n\n" +
                        "*Signal:* %s Strike %d\n" +
                        "*Entry Premium:* %.2f (Nifty Spot at Entry: %.2f)\n" +
                        "*Current Premium:* %.2f (Current Nifty Spot: %.2f)\n" +
                        "*Target 2:* %.2f\n" +
                        "*Gross P&L:* +%.2f INR  |  *Costs:* %.2f INR\n" +
                        "*Net P&L:* %.2f INR\n" +
                        "*Holding Time:* %d seconds",
                        signal.getSignalType(), signal.getStrike(), signal.getEntry(), entrySpot,
                        currentOptionPrice, spot, signal.getTarget2(), tgtGross, tgtCost, tgtNet, result.getHoldingTime());
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
        double high = todaySnapshots.stream().mapToDouble(MarketSnapshot::getNiftySpot).max()
                .orElse(latest.getNiftySpot());
        double low = todaySnapshots.stream().mapToDouble(MarketSnapshot::getNiftySpot).min()
                .orElse(latest.getNiftySpot());

        List<TradeSignal> activeTrades = tradeSignalRepository.findByStatus("ACTIVE");
        List<TradeSignal> todayTrades = tradeSignalRepository.findBySignalTimeAfter(todayStart);
        List<TradeSignal> resolvedTodayTrades = todayTrades.stream()
                .filter(t -> !"ACTIVE".equals(t.getStatus()))
                .toList();

        String aiSummary = llmService.generateMarketSummary(
                latest.getNiftySpot(), open, high, low,
                latest.getRsi(), latest.getVwap(), latest.getEma20(), latest.getEma50(),
                activeTrades, resolvedTodayTrades);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *NIFTY 30-MINUTE MARKET UPDATE* 📊\n\n");
        sb.append("💰 *Nifty Spot Prices:*\n");
        sb.append(String.format("• *Open:* `%.2f`\n", open));
        sb.append(String.format("• *High:* `%.2f`\n", high));
        sb.append(String.format("• *Low:* `%.2f`\n", low));
        sb.append(String.format("• *Current (Running):* `%.2f`\n\n", latest.getNiftySpot()));

        sb.append("📈 *Technical Indicators:*\n");
        sb.append(String.format("• *RSI (14):* `%.2f`\n", latest.getRsi() != null ? latest.getRsi() : 50.0));
        sb.append(String.format("• *VWAP:* `%.2f`\n",
                latest.getVwap() != null ? latest.getVwap() : latest.getNiftySpot()));
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
                        t.getSignalType(), t.getStrike(), t.getEntry(), currentOptionPrice, t.getStopLoss(),
                        t.getTarget2()));
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
