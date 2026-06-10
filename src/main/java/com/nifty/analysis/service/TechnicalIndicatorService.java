package com.nifty.analysis.service;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorService {

    private final MarketSnapshotRepository marketSnapshotRepository;

    /**
     * Calculates EMA for a given period.
     * Formula: EMA_today = (Price_today * alpha) + (EMA_yesterday * (1 - alpha))
     */
    public double calculateEma(double currentPrice, Double previousEma, int period) {
        if (previousEma == null || previousEma == 0.0) {
            return currentPrice;
        }
        double alpha = 2.0 / (period + 1);
        return (currentPrice * alpha) + (previousEma * (1.0 - alpha));
    }

    /**
     * Calculates Wilder's smoothed RSI (14) using historical data relative to
     * current time.
     */
    public double calculateRsi(double currentPrice) {
        return calculateRsi(currentPrice, LocalDateTime.now());
    }

    /**
     * Calculates Wilder's smoothed RSI (14) using historical data prior to the
     * evaluation time.
     */
    public double calculateRsi(double currentPrice, LocalDateTime evaluationTime) {
        // Fetch last 29 historical snapshots before the evaluation time
        List<MarketSnapshot> history = marketSnapshotRepository.findHistoryBefore(
                evaluationTime,
                PageRequest.of(0, 29));

        if (history.size() < 14) {
            log.debug("Insufficient history to calculate RSI. History size: {}", history.size());
            return 50.0; // Return neutral RSI
        }

        // Reconstruct chronological list of prices: historical first, then current
        // price
        List<Double> prices = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            prices.add(history.get(i).getNiftySpot());
        }
        prices.add(currentPrice);

        // Calculate gains and losses
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            if (diff > 0) {
                gains.add(diff);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(-diff);
            }
        }

        // Calculate initial average gain/loss over first 14 periods
        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 0; i < 14; i++) {
            avgGain += gains.get(i);
            avgLoss += losses.get(i);
        }
        avgGain /= 14.0;
        avgLoss /= 14.0;

        // Apply Wilder's smoothing for remaining periods
        for (int i = 14; i < gains.size(); i++) {
            avgGain = (avgGain * 13.0 + gains.get(i)) / 14.0;
            avgLoss = (avgLoss * 13.0 + losses.get(i)) / 14.0;
        }

        if (avgLoss == 0.0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return Math.round((100.0 - (100.0 / (1.0 + rs))) * 100.0) / 100.0;
    }

    /**
     * Calculates Volume Weighted Average Price (VWAP) reset daily at 09:15 AM
     * relative to current time.
     */
    public double calculateVwap(double currentPrice, double currentVolume) {
        return calculateVwap(currentPrice, currentVolume, LocalDateTime.now());
    }

    /**
     * Calculates Volume Weighted Average Price (VWAP) reset daily at 09:15 AM
     * relative to the evaluation time.
     */
    public double calculateVwap(double currentPrice, double currentVolume, LocalDateTime evaluationTime) {
        LocalDateTime todayMarketOpen = evaluationTime.toLocalDate().atTime(LocalTime.of(9, 15));

        // If evaluation time is before 09:15 AM, return currentPrice
        if (evaluationTime.isBefore(todayMarketOpen)) {
            return currentPrice;
        }

        // Fetch all market snapshots between today's market open and the evaluation
        // time
        List<MarketSnapshot> todaySnapshots = marketSnapshotRepository.findBetween(todayMarketOpen, evaluationTime);

        double sumSpotVolume = currentPrice * currentVolume;
        double sumVolume = currentVolume;

        for (MarketSnapshot snapshot : todaySnapshots) {
            double vol = snapshot.getVolume() != null ? snapshot.getVolume() : 0.0;
            sumSpotVolume += snapshot.getNiftySpot() * vol;
            sumVolume += vol;
        }

        if (sumVolume == 0.0) {
            return currentPrice;
        }

        return Math.round((sumSpotVolume / sumVolume) * 100.0) / 100.0;
    }

    /**
     * Calculates the daily return of the previous trading day.
     * Formula: (Close_yesterday - Open_yesterday) / Open_yesterday
     */
    public double calculateYesterdayDailyReturn(LocalDateTime evaluationTime) {
        LocalDateTime todayOpen = evaluationTime.toLocalDate().atTime(9, 15);

        // Yesterday's close is the latest snapshot before today's market open
        Optional<MarketSnapshot> yesterdayCloseSnap = marketSnapshotRepository.findLatestBefore(todayOpen);
        if (yesterdayCloseSnap.isEmpty()) {
            return 0.0;
        }

        // Find the open snapshot from the same day as yesterday's close
        LocalDateTime yesterdayOpenTime = yesterdayCloseSnap.get().getSnapshotTime().toLocalDate().atTime(9, 15);
        Optional<MarketSnapshot> yesterdayOpenSnap = marketSnapshotRepository
                .findLatestBefore(yesterdayOpenTime.plusMinutes(15));

        if (yesterdayOpenSnap.isEmpty()) {
            // Fallback: search for any snapshot around that time or just use the close snap
            // as open
            return 0.0;
        }

        double open = yesterdayOpenSnap.get().getNiftySpot();
        double close = yesterdayCloseSnap.get().getNiftySpot();
        if (open == 0.0) {
            return 0.0;
        }
        return (close - open) / open;
    }

    /**
     * Calculates Bollinger Band Width (20-period standard deviation / 20-period SMA)
     */
    public double calculateBollingerBandWidth(double currentPrice, LocalDateTime evaluationTime) {
        List<MarketSnapshot> history = marketSnapshotRepository.findHistoryBefore(evaluationTime, PageRequest.of(0, 19));
        if (history.isEmpty()) {
            return 0.001; // Avoid division by zero, standard fallback
        }
        List<Double> prices = new ArrayList<>();
        prices.add(currentPrice);
        for (MarketSnapshot snapshot : history) {
            prices.add(snapshot.getNiftySpot());
        }
        double mean = prices.stream().mapToDouble(Double::doubleValue).average().orElse(currentPrice);
        double varianceSum = 0.0;
        for (double price : prices) {
            varianceSum += Math.pow(price - mean, 2);
        }
        double stdDev = Math.sqrt(varianceSum / prices.size());
        return Math.round(((2.0 * stdDev) / (mean + 1e-9)) * 10000.0) / 10000.0;
    }

    /**
     * Calculates MACD Histogram (MACD line - Signal line)
     */
    public double calculateMacdHistogram(double currentPrice, LocalDateTime evaluationTime) {
        List<MarketSnapshot> history = marketSnapshotRepository.findHistoryBefore(evaluationTime, PageRequest.of(0, 50));
        if (history.isEmpty()) {
            return 0.0;
        }
        // Reconstruct chronological list of prices: oldest first, then current price
        List<Double> prices = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            prices.add(history.get(i).getNiftySpot());
        }
        prices.add(currentPrice);

        // Calculate EMA12 and EMA26 and the MACD line
        List<Double> macdLine = new ArrayList<>();
        double ema12 = prices.get(0);
        double ema26 = prices.get(0);
        double alpha12 = 2.0 / (12.0 + 1.0);
        double alpha26 = 2.0 / (26.0 + 1.0);

        macdLine.add(ema12 - ema26);

        for (int i = 1; i < prices.size(); i++) {
            double price = prices.get(i);
            ema12 = (price * alpha12) + (ema12 * (1.0 - alpha12));
            ema26 = (price * alpha26) + (ema26 * (1.0 - alpha26));
            macdLine.add(ema12 - ema26);
        }

        // Calculate Signal Line (EMA9 of MACD line)
        double signal = macdLine.get(0);
        double alpha9 = 2.0 / (9.0 + 1.0);
        for (int i = 1; i < macdLine.size(); i++) {
            signal = (macdLine.get(i) * alpha9) + (signal * (1.0 - alpha9));
        }

        double latestMacd = macdLine.get(macdLine.size() - 1);
        return Math.round((latestMacd - signal) * 100.0) / 100.0;
    }

    /**
     * Calculates the Volume Ratio (current volume / 20-period average volume)
     */
    public double calculateVolumeRatio(double currentVolume, LocalDateTime evaluationTime) {
        List<MarketSnapshot> history = marketSnapshotRepository.findHistoryBefore(evaluationTime, PageRequest.of(0, 19));
        if (history.isEmpty()) {
            return 1.0;
        }
        double volumeSum = currentVolume;
        for (MarketSnapshot snapshot : history) {
            volumeSum += snapshot.getVolume() != null ? snapshot.getVolume() : 0.0;
        }
        double meanVolume = volumeSum / (history.size() + 1);
        if (meanVolume == 0.0) {
            return 1.0;
        }
        return Math.round((currentVolume / meanVolume) * 100.0) / 100.0;
    }
}

