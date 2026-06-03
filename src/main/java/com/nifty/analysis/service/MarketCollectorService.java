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
    
    private final RedisService redisService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final OptionsIndicatorService optionsIndicatorService;
    private final DecisionAgent decisionAgent;

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
                return option;
            }).toList();
            
            optionSnapshotRepository.saveAll(optionSnapshots);
            redisService.saveLatestOptionChain(optionSnapshots);
            log.info("Option Chain snapshot persisted ({} strikes, Max Pain={})", optionSnapshots.size(), calculatedMaxPain);

            // 3. Update Market Candle (5-minute Timeframe)
            updateCandle(marketData.niftySpot(), marketData.volume(), now, "5m");

            // 4. Trigger Decision Agent signal evaluations
            decisionAgent.evaluateMarketForSignals(snapshot, prevSnapshot.map(MarketSnapshot::getNiftySpot).orElse(null));

        } catch (Exception e) {
            log.error("Error during data collection cycle", e);
        }
    }

    private void updateCandle(double spot, double totalVolume, LocalDateTime now, String timeframe) {
        // Calculate the start time of the current 5-minute candle
        LocalDateTime candleStart = roundToTimeframe(now, 5);
        
        // Find existing candle for the timeframe and start time
        List<MarketCandle> existingCandles = marketCandleRepository.findLatestByTimeframe(timeframe, 1);
        
        MarketCandle candle;
        if (!existingCandles.isEmpty() && existingCandles.getFirst().getTimestamp().equals(candleStart)) {
            candle = existingCandles.getFirst();
            // Update candle
            candle.setHigh(Math.max(candle.getHigh(), spot));
            candle.setLow(Math.min(candle.getLow(), spot));
            candle.setClose(spot);
            
            // Estimate incremental volume
            double volumeDiff = Math.max(0.0, totalVolume - candle.getVolume());
            candle.setVolume(candle.getVolume() + volumeDiff);
        } else {
            // Create a new candle
            candle = new MarketCandle();
            candle.setTimestamp(candleStart);
            candle.setOpen(spot);
            candle.setHigh(spot);
            candle.setLow(spot);
            candle.setClose(spot);
            candle.setVolume(0.0); // Will align with volume updates
            candle.setTimeframe(timeframe);
        }
        
        marketCandleRepository.save(candle);
        log.debug("Candle ({}) updated: Timestamp={}, Open={}, Close={}", 
                timeframe, candle.getTimestamp(), candle.getOpen(), candle.getClose());
    }

    private LocalDateTime roundToTimeframe(LocalDateTime time, int minutes) {
        int roundedMinute = (time.getMinute() / minutes) * minutes;
        return time.truncatedTo(ChronoUnit.HOURS)
                .plusMinutes(roundedMinute)
                .withSecond(0)
                .withNano(0);
    }
}
