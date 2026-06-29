package com.nifty.analysis.collector.client.impl;

import com.nifty.analysis.collector.client.MarketDataClient;
import com.nifty.analysis.collector.client.OptionChainClient;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.instrument.InstrumentRegistry;
import com.nifty.analysis.instrument.InstrumentSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "nifty.collector.provider",
        havingValue = "simulated",
        matchIfMissing = true
)
public class SimulatedDataClient implements MarketDataClient, OptionChainClient {

    private final InstrumentRegistry instrumentRegistry;
    private final Random random = new Random();

    /** Per-instrument simulation state so NIFTY and BANKNIFTY evolve independently. */
    private final Map<String, InstrumentState> states = new HashMap<>();

    private static class InstrumentState {
        double spot;
        double vix = 13.5;
        double volume = 500000.0;
        final List<long[]> strikeOi = new ArrayList<>(); // [strike, lastCeOi, lastPeOi]
        InstrumentState(double spot) { this.spot = spot; }
    }

    /** Reasonable starting index level per instrument. */
    private double baselineSpot(String instrument) {
        return "BANKNIFTY".equals(instrument) ? 51000.0 : 23500.0;
    }

    private synchronized InstrumentState state(String instrument) {
        return states.computeIfAbsent(instrument, i -> new InstrumentState(baselineSpot(i)));
    }

    private int strikeStep(String instrument) {
        InstrumentSpec spec = instrumentRegistry.get(instrument);
        return spec != null ? spec.strikeStep() : 50;
    }

    @Override
    public synchronized MarketSnapshotDto fetchMarketData(String instrument) {
        InstrumentState s = state(instrument);
        // Random walk scaled to the instrument's level (Bank Nifty moves in bigger points).
        double scale = s.spot / 23500.0;
        s.spot = Math.round((s.spot + (random.nextDouble() - 0.5) * 30.0 * scale) * 100.0) / 100.0;
        s.vix = Math.max(10.0, Math.min(25.0, Math.round((s.vix + (random.nextDouble() - 0.5) * 0.4) * 100.0) / 100.0));
        s.volume += Math.round(random.nextDouble() * 50000.0);

        double futurePremium = (25.0 + random.nextDouble() * 15.0) * scale;
        double futurePrice = Math.round((s.spot + futurePremium) * 100.0) / 100.0;
        return new MarketSnapshotDto(s.spot, futurePrice, s.vix, s.volume, com.nifty.analysis.util.TimeUtil.nowIst());
    }

    @Override
    public synchronized List<OptionSnapshotDto> fetchOptionChain(String instrument) {
        InstrumentState s = state(instrument);
        int step = strikeStep(instrument);
        LocalDateTime now = com.nifty.analysis.util.TimeUtil.nowIst();
        int atmStrike = (int) (Math.round(s.spot / step) * step);

        List<OptionSnapshotDto> snapshots = new ArrayList<>();
        for (int i = -20; i <= 20; i++) {
            int strike = atmStrike + (i * step);
            long baseCeOi = (long) (1000000 * Math.exp(-Math.pow(i - 2, 2) / 8.0));
            long basePeOi = (long) (1000000 * Math.exp(-Math.pow(i + 2, 2) / 8.0));
            long ceOi = Math.max(10000L, baseCeOi + random.nextInt(50000));
            long peOi = Math.max(10000L, basePeOi + random.nextInt(50000));

            long[] prev = findOrCreateStrike(s, strike, ceOi, peOi);
            long ceOiChange = ceOi - prev[1];
            long peOiChange = peOi - prev[2];
            prev[1] = ceOi;
            prev[2] = peOi;

            double iv = Math.round((10.0 + (Math.abs(i) * 0.8) + random.nextDouble() * 0.5) * 100.0) / 100.0;
            double pcr = ceOi > 0 ? Math.round(((double) peOi / ceOi) * 100.0) / 100.0 : 0.0;
            long ceVolume = Math.max(5000L, ceOi / 10 + random.nextInt(10000));
            long peVolume = Math.max(5000L, peOi / 10 + random.nextInt(10000));

            double ceLtp = Math.max(2.0, Math.round((s.spot - strike + 120.0) * 100.0) / 100.0);
            double peLtp = Math.max(2.0, Math.round((strike - s.spot + 120.0) * 100.0) / 100.0);
            snapshots.add(new OptionSnapshotDto(strike, ceOi, peOi, ceOiChange, peOiChange,
                    iv, pcr, (double) atmStrike, ceVolume, peVolume, now, ceLtp, peLtp));
        }
        return snapshots;
    }

    private long[] findOrCreateStrike(InstrumentState s, int strike, long ceOi, long peOi) {
        for (long[] st : s.strikeOi) {
            if (st[0] == strike) {
                return st;
            }
        }
        long[] created = new long[]{strike, ceOi, peOi};
        s.strikeOi.add(created);
        return created;
    }
}
