package com.nifty.analysis.collector.client.impl;

import com.nifty.analysis.collector.client.MarketDataClient;
import com.nifty.analysis.collector.client.OptionChainClient;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.dto.OptionSnapshotDto;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "nifty.collector.provider",
        havingValue = "simulated",
        matchIfMissing = true
)
public class SimulatedDataClient implements MarketDataClient, OptionChainClient {

    private final Random random = new Random();
    
    // Baselines
    private double currentSpot = 23500.0;
    private double currentVix = 13.5;
    private double currentVolume = 500000.0;
    
    // Keep track of previous OIs to simulate realistic changes
    private final List<StrikeState> strikeStates = new ArrayList<>();

    private synchronized void updateMarketState() {
        // Random walk: spot moves by -15 to +15 per interval
        double change = (random.nextDouble() - 0.5) * 30.0;
        currentSpot = Math.round((currentSpot + change) * 100.0) / 100.0;
        
        // VIX moves by -0.2 to +0.2, bounded between 10.0 and 25.0
        double vixChange = (random.nextDouble() - 0.5) * 0.4;
        currentVix = Math.max(10.0, Math.min(25.0, Math.round((currentVix + vixChange) * 100.0) / 100.0));
        
        // Volume grows
        currentVolume += Math.round(random.nextDouble() * 50000.0);
    }

    @Override
    public synchronized MarketSnapshotDto fetchMarketData() {
        updateMarketState();
        double futurePremium = 25.0 + (random.nextDouble() * 15.0); // premium of 25 to 40 points
        double futurePrice = Math.round((currentSpot + futurePremium) * 100.0) / 100.0;
        
        return new MarketSnapshotDto(
                currentSpot,
                futurePrice,
                currentVix,
                currentVolume,
                LocalDateTime.now()
        );
    }

    @Override
    public synchronized List<OptionSnapshotDto> fetchOptionChain() {
        LocalDateTime now = LocalDateTime.now();
        // Determine ATM strike (Nifty strikes are in multiples of 50)
        int atmStrike = ((int) Math.round(currentSpot / 50.0)) * 50;
        
        List<OptionSnapshotDto> snapshots = new ArrayList<>();
        
        // Generate 10 strikes below and 10 strikes above ATM (21 strikes total)
        for (int i = -10; i <= 10; i++) {
            int strike = atmStrike + (i * 50);
            
            // Generate baseline Open Interest (OI)
            // Call OI is higher at resistance strikes above ATM
            // Put OI is higher at support strikes below ATM
            long baseCeOi = (long) (1000000 * Math.exp(-Math.pow(i - 2, 2) / 8.0));
            long basePeOi = (long) (1000000 * Math.exp(-Math.pow(i + 2, 2) / 8.0));
            
            // Random variance
            long ceOi = Math.max(10000L, baseCeOi + random.nextInt(50000));
            long peOi = Math.max(10000L, basePeOi + random.nextInt(50000));
            
            // Find or initialize strike historical state to determine change
            StrikeState state = findOrCreateStrikeState(strike, ceOi, peOi);
            long ceOiChange = ceOi - state.lastCeOi;
            long peOiChange = peOi - state.lastPeOi;
            
            // Update last seen values
            state.lastCeOi = ceOi;
            state.lastPeOi = peOi;
            
            // Implied Volatility (IV) - higher for far OTM and ITM options
            double iv = 10.0 + (Math.abs(i) * 0.8) + (random.nextDouble() * 0.5);
            iv = Math.round(iv * 100.0) / 100.0;
            
            // Put-Call Ratio (PCR) per strike
            double pcr = ceOi > 0 ? (double) peOi / ceOi : 0.0;
            pcr = Math.round(pcr * 100.0) / 100.0;
            
            // Max pain is centered around ATM generally
            double maxPain = atmStrike;

            long ceVolume = Math.max(5000L, ceOi / 10 + random.nextInt(10000));
            long peVolume = Math.max(5000L, peOi / 10 + random.nextInt(10000));
            
            snapshots.add(new OptionSnapshotDto(
                    strike,
                    ceOi,
                    peOi,
                    ceOiChange,
                    peOiChange,
                    iv,
                    pcr,
                    maxPain,
                    ceVolume,
                    peVolume,
                    now
            ));
        }
        
        return snapshots;
    }

    private StrikeState findOrCreateStrikeState(int strike, long ceOi, long peOi) {
        for (StrikeState state : strikeStates) {
            if (state.strike == strike) {
                return state;
            }
        }
        // Create new
        StrikeState newState = new StrikeState(strike, ceOi, peOi);
        strikeStates.add(newState);
        return newState;
    }

    private static class StrikeState {
        int strike;
        long lastCeOi;
        long lastPeOi;

        StrikeState(int strike, long ceOi, long peOi) {
            this.strike = strike;
            this.lastCeOi = ceOi;
            this.lastPeOi = peOi;
        }
    }
}
