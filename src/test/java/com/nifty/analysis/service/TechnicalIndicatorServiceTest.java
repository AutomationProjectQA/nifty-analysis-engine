package com.nifty.analysis.service;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TechnicalIndicatorServiceTest {

    @Mock
    private MarketSnapshotRepository marketSnapshotRepository;

    private TechnicalIndicatorService technicalIndicatorService;

    @BeforeEach
    void setUp() {
        technicalIndicatorService = new TechnicalIndicatorService(marketSnapshotRepository);
    }

    @Test
    void testCalculateEmaFirstValue() {
        double current = 23500.0;
        double ema = technicalIndicatorService.calculateEma(current, null, 20);
        assertEquals(current, ema);
    }

    @Test
    void testCalculateEmaNormalValue() {
        double current = 23520.0;
        double prevEma = 23500.0;
        double ema = technicalIndicatorService.calculateEma(current, prevEma, 20);
        // Alpha = 2 / 21 = 0.095238
        // EMA = 23520 * 0.095238 + 23500 * (1 - 0.095238) = 2240 + 21262.24 = 23501.9
        assertEquals(23501.9, ema, 0.1);
    }

    @Test
    void testCalculateRsiInsufficientHistory() {
        when(marketSnapshotRepository.findHistoryBefore(any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());
        double rsi = technicalIndicatorService.calculateRsi(23500.0);
        assertEquals(50.0, rsi);
    }

    @Test
    void testCalculateRsiSuccess() {
        // Arrange: build 14 snapshots with alternating prices to verify calculation works
        List<MarketSnapshot> history = new ArrayList<>();
        double basePrice = 23500.0;
        for (int i = 0; i < 14; i++) {
            MarketSnapshot s = new MarketSnapshot();
            // Alternate spot prices up and down
            s.setNiftySpot(basePrice + (i % 2 == 0 ? 10.0 : -10.0));
            s.setSnapshotTime(LocalDateTime.now().minusMinutes(20 - i));
            history.add(s);
        }
        
        when(marketSnapshotRepository.findHistoryBefore(any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(history);

        // Act
        double rsi = technicalIndicatorService.calculateRsi(23510.0);

        // Assert
        assertTrue(rsi >= 0.0 && rsi <= 100.0);
    }

    @Test
    void testCalculateVwapSuccess() {
        // Arrange
        LocalDateTime evaluationTime = LocalDateTime.now().toLocalDate().atTime(10, 0);

        MarketSnapshot s1 = new MarketSnapshot();
        s1.setNiftySpot(23500.0);
        s1.setVolume(100.0);
        s1.setSnapshotTime(evaluationTime.minusMinutes(5));

        MarketSnapshot s2 = new MarketSnapshot();
        s2.setNiftySpot(23510.0);
        s2.setVolume(200.0);
        s2.setSnapshotTime(evaluationTime.minusMinutes(2));

        when(marketSnapshotRepository.findBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(s1, s2));

        // Act
        double vwap = technicalIndicatorService.calculateVwap(23520.0, 300.0, evaluationTime);

        // Assert
        // Total Volume = 100 + 200 + 300 = 600
        // Total Spot*Volume = (23500 * 100) + (23510 * 200) + (23520 * 300) = 2350000 + 4702000 + 7056000 = 14108000
        // VWAP = 14108000 / 600 = 23513.33
        assertEquals(23513.33, vwap, 0.01);
    }

    @Test
    void testCalculateHourlyFeatures() {
        List<MarketSnapshot> snapshots = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 11, 9, 15, 0);
        
        for (int i = 0; i < 60; i++) {
            MarketSnapshot s = new MarketSnapshot();
            s.setSnapshotTime(baseTime.plusHours(i));
            s.setNiftySpot(23000.0 + i * 5.0);
            s.setIndiaVix(15.0);
            s.setVolume(1000.0 * (i + 1));
            snapshots.add(s);
        }
        
        MarketSnapshot latest = snapshots.get(snapshots.size() - 1);
        var features = technicalIndicatorService.calculateHourlyFeatures(latest, snapshots);
        
        assertNotNull(features);
        assertTrue(features.rsi() >= 0.0 && features.rsi() <= 100.0);
        assertTrue(features.spotToEma20() > 1.0);
        assertTrue(features.ema20ToEma50() > 1.0);
        assertEquals(15.0, features.vix());
    }
}
