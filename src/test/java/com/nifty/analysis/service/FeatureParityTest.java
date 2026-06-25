package com.nifty.analysis.service;

import com.nifty.analysis.agent.TechnicalAgent;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Golden-master parity test: pins the Java feature extraction to the documented values in
 * docs/FEATURE_SPEC.md. If a feature definition drifts (breaking parity with the Python
 * training pipeline), this test fails. The Python side computes the SAME 8 features in the
 * SAME order — see train_model.py / FEATURE_SPEC.md.
 */
@ExtendWith(MockitoExtension.class)
class FeatureParityTest {

    @Mock private MarketSnapshotRepository marketSnapshotRepository;

    @InjectMocks private TechnicalIndicatorService technicalIndicatorService;

    private MarketSnapshot snap(LocalDateTime t, double spot, double vix, double vol) {
        MarketSnapshot s = new MarketSnapshot();
        s.setSnapshotTime(t);
        s.setNiftySpot(spot);
        s.setIndiaVix(vix);
        s.setVolume(vol);
        return s;
    }

    @Test
    void hourlyFeatures_matchSpecFallbacks_forFlatSingleCandle() {
        // No prior daily snapshot -> Prev_Daily_Return = 0.0
        lenient().when(marketSnapshotRepository.findLatestBefore(any())).thenReturn(Optional.empty());

        // Three flat snapshots all in the same hourly candle window (10:15), close = 23500.
        LocalDateTime eval = LocalDateTime.of(2026, 6, 25, 11, 0);
        List<MarketSnapshot> all = List.of(
                snap(LocalDateTime.of(2026, 6, 25, 10, 30), 23500.0, 14.0, 1000.0),
                snap(LocalDateTime.of(2026, 6, 25, 10, 45), 23500.0, 14.0, 1000.0),
                snap(eval, 23500.0, 14.0, 1000.0)
        );
        MarketSnapshot latest = snap(eval, 23500.0, 14.0, 1000.0);

        TechnicalAgent.TechnicalFeatures f = technicalIndicatorService.calculateHourlyFeatures(latest, all);

        // Expected = the documented fallbacks for a single flat candle (see FEATURE_SPEC.md).
        assertEquals(50.0, f.rsi(), 1e-9);          // < 15 candles -> 50
        assertEquals(1.0, f.spotToEma20(), 1e-9);   // spot == ema20 (flat) -> 1.0
        assertEquals(1.0, f.ema20ToEma50(), 1e-9);  // ema20 == ema50 -> 1.0
        assertEquals(14.0, f.vix(), 1e-9);          // from latest snapshot
        assertEquals(0.0, f.prevDailyReturn(), 1e-9); // no prior day -> 0.0
        assertEquals(0.015, f.bbWidth(), 1e-9);     // < 20 candles -> 0.015
        assertEquals(0.0, f.macdHist(), 1e-9);      // single flat candle -> 0.0
        assertEquals(1.0, f.volumeRatio(), 1e-9);   // < 20 candles -> 1.0
    }
}
