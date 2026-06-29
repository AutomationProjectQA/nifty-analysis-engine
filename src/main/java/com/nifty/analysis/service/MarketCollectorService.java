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
    private final com.nifty.analysis.repository.TradeLegRepository tradeLegRepository;
    private final com.nifty.analysis.instrument.InstrumentRegistry instrumentRegistry;
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

    // P5-5: reject a stale/frozen feed before opening new positions. A tick is stale if it is
    // older than this many seconds, or not newer than the previous snapshot (duplicate/frozen feed).
    @org.springframework.beans.factory.annotation.Value("${nifty.collector.max-staleness-seconds:120}")
    private long maxStalenessSeconds;

    // P5-5d: run the SLOW decision step (LLM thesis + order placement) off the collection thread so a
    // slow cycle never drops a minute of snapshot/candle data. Guarded + single-threaded so decisions
    // can't overlap (no double-open) and can't pile up (stale evaluations are skipped, not queued).
    @org.springframework.beans.factory.annotation.Value("${nifty.collector.async-decisions:true}")
    private boolean asyncDecisions;
    private final java.util.concurrent.atomic.AtomicBoolean decisionRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private java.util.concurrent.Executor decisionExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "decision-runner");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    public void shutdownDecisionExecutor() {
        if (decisionExecutor instanceof java.util.concurrent.ExecutorService es) {
            es.shutdown();
        }
    }

    // Shared non-reentrancy guard: the 1-min scheduler AND the intraday event trigger (P5-3) both
    // run cycles through tryCollect(), so they can never overlap and double-open positions.
    private final java.util.concurrent.atomic.AtomicBoolean cycleRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Runs a collection cycle unless one is already in progress. Returns true if it actually ran.
     * Callers (scheduler, event trigger) share this guard so cycles are strictly serialized.
     */
    public boolean tryCollect() {
        if (!cycleRunning.compareAndSet(false, true)) {
            log.warn("Collection cycle already running — skipping this trigger to avoid overlap.");
            return false;
        }
        try {
            collect();
            return true;
        } finally {
            cycleRunning.set(false);
        }
    }

    // NOT @Transactional on purpose: this cycle interleaves blocking external HTTP (Angel
    // One, LLM, Telegram, order placement) with DB writes. Wrapping it all in one
    // transaction would pin a DB connection across those slow calls (pool exhaustion).
    // Each persistence step commits independently; tryCollect() ensures the cycle
    // is never re-entered concurrently.
    public void collect() {
        log.info("Starting market and option chain data collection cycle...");
        // P5-2: run the pipeline for every enabled instrument (NIFTY always; BANKNIFTY when enabled).
        for (com.nifty.analysis.instrument.InstrumentSpec spec : instrumentRegistry.active()) {
            collectForInstrument(spec);
        }
        // Push the current (all-instrument) signal set to the portal once per cycle.
        marketStreamPublisher.publishSignals(tradeSignalRepository.findAllByOrderBySignalTimeDesc());
    }

    private void collectForInstrument(com.nifty.analysis.instrument.InstrumentSpec spec) {
        String instrument = spec.name();
        try {
            LocalDateTime now = com.nifty.analysis.util.TimeUtil.nowIst();

            // 1. Fetch & Store Market Snapshot (per instrument)
            MarketSnapshotDto marketData = marketDataClient.fetchMarketData(instrument);

            // Previous snapshot for THIS instrument (prev-spot delta + freshness baseline).
            Optional<MarketSnapshot> prevSnapshot = marketSnapshotRepository.findLatestByInstrument(instrument);

            // Indicators on the instrument's 5m candle series.
            double ema20 = technicalIndicatorService.calculateEmaFromCandles(instrument, "5m", 20, marketData.timestamp(), marketData.niftySpot());
            double ema50 = technicalIndicatorService.calculateEmaFromCandles(instrument, "5m", 50, marketData.timestamp(), marketData.niftySpot());
            double rsi = technicalIndicatorService.calculateRsiFromCandles(instrument, "5m", 14, marketData.timestamp(), marketData.niftySpot());
            double vwap = technicalIndicatorService.calculateVwap(instrument, marketData.niftySpot(), marketData.volume(),
                    marketData.timestamp());

            MarketSnapshot snapshot = new MarketSnapshot();
            snapshot.setInstrument(instrument);
            snapshot.setSnapshotTime(marketData.timestamp());
            snapshot.setNiftySpot(marketData.niftySpot());
            snapshot.setNiftyFuture(marketData.niftyFuture());
            snapshot.setIndiaVix(marketData.indiaVix());
            snapshot.setVolume(marketData.volume());
            snapshot.setEma20(ema20);
            snapshot.setEma50(ema50);
            snapshot.setRsi(rsi);
            snapshot.setVwap(vwap);

            marketSnapshotRepository.save(snapshot);
            redisService.saveLatestMarketSnapshot(snapshot);
            marketStreamPublisher.publishMarket(snapshot); // live push to the portal
            log.info("[{}] Snapshot persisted: Spot={}, Future={}, VIX={}, EMA20={}, EMA50={}, RSI={}, VWAP={}",
                    instrument, snapshot.getNiftySpot(), snapshot.getNiftyFuture(), snapshot.getIndiaVix(),
                    snapshot.getEma20(), snapshot.getEma50(), snapshot.getRsi(), snapshot.getVwap());

            // 2. Fetch & Store Option Chain Snapshots (per instrument)
            List<OptionSnapshotDto> optionChainData = optionChainClient.fetchOptionChain(instrument);
            double calculatedMaxPain = optionsIndicatorService.calculateMaxPain(optionChainData);

            List<OptionSnapshot> optionSnapshots = optionChainData.stream().map(dto -> {
                OptionSnapshot option = new OptionSnapshot();
                option.setInstrument(instrument);
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
            marketStreamPublisher.publishOptions(optionSnapshots);
            log.info("[{}] Option chain persisted ({} strikes, Max Pain={})", instrument, optionSnapshots.size(),
                    calculatedMaxPain);

            // 3. Update the instrument's candles (5m/15m/30m/60m)
            updateCandle(instrument, marketData.niftySpot(), marketData.volume(), now, "5m");
            updateCandle(instrument, marketData.niftySpot(), marketData.volume(), now, "15m");
            updateCandle(instrument, marketData.niftySpot(), marketData.volume(), now, "30m");
            updateCandle(instrument, marketData.niftySpot(), marketData.volume(), now, "60m");

            // 4. Decision step (fresh feed only; off-loaded so a slow LLM can't drop a minute).
            LocalDateTime prevTime = prevSnapshot.map(MarketSnapshot::getSnapshotTime).orElse(null);
            if (isFeedStale(marketData.timestamp(), prevTime, now, maxStalenessSeconds)) {
                log.warn("[{}] Stale/frozen feed (tick={}, prev={}). Skipping signal generation.", instrument,
                        marketData.timestamp(), prevTime);
            } else {
                dispatchDecision(snapshot, prevSnapshot.map(MarketSnapshot::getNiftySpot).orElse(null));
            }

            // 5. Resolve the instrument's active trades.
            updateActiveTrades(snapshot);

        } catch (Exception e) {
            log.error("Error during {} collection cycle", instrument, e);
        }
    }

    private void updateCandle(String instrument, double spot, double totalVolume, LocalDateTime now, String timeframe) {
        int minutes = 5;
        if ("15m".equals(timeframe)) {
            minutes = 15;
        } else if ("30m".equals(timeframe)) {
            minutes = 30;
        } else if ("60m".equals(timeframe)) {
            minutes = 60;
        }
        LocalDateTime candleStart = roundToTimeframe(now, minutes);

        // Find volume at the start of the timeframe (instrument-scoped)
        double volumeBefore = 0.0;
        Optional<MarketSnapshot> prevSnap = marketSnapshotRepository.findLatestBeforeByInstrument(instrument, candleStart);
        if (prevSnap.isPresent()) {
            if (prevSnap.get().getSnapshotTime().toLocalDate().equals(candleStart.toLocalDate())) {
                volumeBefore = prevSnap.get().getVolume() != null ? prevSnap.get().getVolume() : 0.0;
            }
        }
        double currentCandleVolume = Math.max(0.0, totalVolume - volumeBefore);

        // Find existing candle for this instrument + timeframe + start time
        List<MarketCandle> existingCandles = marketCandleRepository.findLatestByInstrumentAndTimeframe(instrument, timeframe, 1);

        MarketCandle candle;
        if (!existingCandles.isEmpty() && existingCandles.getFirst().getTimestamp().equals(candleStart)) {
            candle = existingCandles.getFirst();
            candle.setHigh(Math.max(candle.getHigh(), spot));
            candle.setLow(Math.min(candle.getLow(), spot));
            candle.setClose(spot);
            candle.setVolume(currentCandleVolume);
        } else {
            candle = new MarketCandle();
            candle.setInstrument(instrument);
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

    /**
     * Runs the decision/evaluation step. Synchronous when {@code asyncDecisions} is off (tests /
     * opt-out). Otherwise off-loads it to a single-thread executor — guarded so a slow LLM/order
     * step can't delay data collection (no dropped minute), can't overlap (no double-open), and
     * can't pile up (a still-running decision means this cycle's evaluation is skipped).
     */
    private void dispatchDecision(MarketSnapshot snapshot, Double prevSpot) {
        if (!asyncDecisions) {
            decisionAgent.evaluateMarketForSignals(snapshot, prevSpot);
            return;
        }
        if (!decisionRunning.compareAndSet(false, true)) {
            log.warn("Previous decision still running — skipping this cycle's evaluation to keep data collection on time.");
            return;
        }
        decisionExecutor.execute(() -> {
            try {
                decisionAgent.evaluateMarketForSignals(snapshot, prevSpot);
            } catch (Exception e) {
                log.error("Async decision evaluation failed", e);
            } finally {
                decisionRunning.set(false);
            }
        });
    }

    /**
     * Feed-freshness check (pure, unit-tested). A tick is stale if it is older than
     * {@code maxStalenessSeconds} relative to now, or is not strictly newer than the previous
     * snapshot (a duplicate/frozen feed repeating the same tick).
     */
    static boolean isFeedStale(LocalDateTime tickTime, LocalDateTime prevTime, LocalDateTime now, long maxStalenessSeconds) {
        if (tickTime == null) {
            return true;
        }
        if (prevTime != null && !tickTime.isAfter(prevTime)) {
            return true; // duplicate / frozen feed
        }
        return java.time.Duration.between(tickTime, now).getSeconds() > maxStalenessSeconds;
    }

    /** True once today (IST) is past the weekly expiry (Tuesday on/after) of the signal's date. */
    private boolean isPastExpiry(LocalDateTime signalTime) {
        if (signalTime == null) {
            return false;
        }
        java.time.LocalDate expiry = com.nifty.analysis.util.TimeUtil.nextWeeklyExpiry(signalTime.toLocalDate());
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
        String instrument = latest.getInstrument() != null ? latest.getInstrument() : "NIFTY";
        log.info("[{}] Checking active trades relative to spot {}...", instrument, latest.getNiftySpot());
        List<TradeSignal> activeSignals = tradeSignalRepository.findByInstrumentAndStatus(instrument, "ACTIVE");
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
            // P5-1: multi-leg (spread / iron condor) signals resolve on combined NET P&L from their legs.
            if (signal.getStrategy() != null) {
                resolveMultiLeg(signal, latest, theoByStrike);
                continue;
            }
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

    /**
     * P5-1: resolve a multi-leg (spread / iron condor) signal on its combined NET P&L. Each leg is
     * priced (live LTP → theoretical fallback); P&L = Σ sign·(current − entry)·qty. Resolves at the
     * INR take-profit (target2), the INR defined-risk cap (stopLoss), or expiry. If any leg can't be
     * priced, leaves it ACTIVE rather than resolving on a partial position.
     */
    private void resolveMultiLeg(TradeSignal signal, MarketSnapshot latest,
            java.util.Map<Integer, com.nifty.analysis.dto.OptionPremiumDto.StrikePremium> theoByStrike) {
        List<com.nifty.analysis.entity.TradeLeg> legs = tradeLegRepository.findBySignalId(signal.getId());
        if (legs.isEmpty()) {
            return;
        }
        double netPnl = 0.0;
        for (com.nifty.analysis.entity.TradeLeg leg : legs) {
            boolean call = "CE".equals(leg.getOptionType());
            double cur = optionPricingService.getOptionLtp(call ? "BUY_CE" : "BUY_PE", leg.getStrike());
            if (cur <= 0) {
                com.nifty.analysis.dto.OptionPremiumDto.StrikePremium theo = theoByStrike.get(leg.getStrike());
                cur = theo != null ? (call ? theo.cePremium() : theo.pePremium()) : -1;
            }
            if (cur <= 0) {
                log.warn("No premium for leg {} {} of multi-leg signal {} — leaving ACTIVE.",
                        leg.getOptionType(), leg.getStrike(), signal.getId());
                return;
            }
            netPnl += leg.sign() * (cur - leg.getEntryPremium()) * leg.getQuantity();
        }
        netPnl = Math.round(netPnl * 100.0) / 100.0;

        double target = signal.getTarget2() != null ? signal.getTarget2() : Double.MAX_VALUE;   // INR take-profit
        double stopCap = signal.getStopLoss() != null ? signal.getStopLoss() : Double.MAX_VALUE; // INR max-loss cap

        String outcome;
        if (isPastExpiry(signal.getSignalTime())) {
            outcome = "EXPIRED";
        } else if (netPnl >= target) {
            outcome = "TARGET2";
        } else if (netPnl <= -stopCap) {
            outcome = "STOP_LOSS";
        } else {
            return; // still open
        }

        signal.setStatus("EXPIRED".equals(outcome) ? "EXPIRED" : ("STOP_LOSS".equals(outcome) ? "FAILED" : "COMPLETED"));
        tradeSignalRepository.save(signal);

        TradeResult result = new TradeResult();
        result.setSignal(signal);
        result.setOutcome(outcome);
        result.setProfitLoss(netPnl);
        result.setHoldingTime(java.time.Duration.between(signal.getSignalTime(), latest.getSnapshotTime()).toSeconds());
        result.setAccuracy(netPnl >= 0 ? 100.0 : 0.0);
        tradeResultRepository.save(result);
        confidenceWeightTuner.reinforce(signal, netPnl >= 0);

        telegramBotService.sendMessage(String.format(
                "%s *MULTI-LEG RESOLVED: %s*%n%n*%s %s* (Strike %d, %d legs)%n*Net P&L:* %.2f INR",
                netPnl >= 0 ? "✅" : "🚨", outcome, signal.getInstrument(), signal.getStrategy(),
                signal.getStrike(), legs.size(), netPnl));
        log.info("Multi-leg {} {} resolved {} for signal {} (net {} INR).",
                signal.getInstrument(), signal.getStrategy(), outcome, signal.getId(), netPnl);
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
