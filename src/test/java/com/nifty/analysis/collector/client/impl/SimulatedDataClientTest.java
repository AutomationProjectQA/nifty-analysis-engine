package com.nifty.analysis.collector.client.impl;

import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.instrument.InstrumentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedDataClientTest {

    // Registry with BANKNIFTY enabled so we can exercise its simulated data.
    private final SimulatedDataClient client = new SimulatedDataClient(new InstrumentRegistry(true));

    @Test
    void niftyChain_strikesAreMultiplesOf50() {
        List<OptionSnapshotDto> chain = client.fetchOptionChain("NIFTY");
        assertTrue(chain.size() > 10);
        assertTrue(chain.stream().allMatch(o -> o.strikePrice() % 50 == 0));
    }

    @Test
    void bankNifty_hasHigherSpotAndStep100Strikes() {
        double bnSpot = client.fetchMarketData("BANKNIFTY").niftySpot();
        assertTrue(bnSpot > 40000.0, "Bank Nifty should trade well above Nifty levels");

        List<OptionSnapshotDto> chain = client.fetchOptionChain("BANKNIFTY");
        assertTrue(chain.size() > 10);
        assertTrue(chain.stream().allMatch(o -> o.strikePrice() % 100 == 0),
                "Bank Nifty strikes must be in steps of 100");
    }

    @Test
    void instrumentsEvolveIndependently() {
        double nifty = client.fetchMarketData("NIFTY").niftySpot();
        double bank = client.fetchMarketData("BANKNIFTY").niftySpot();
        assertTrue(bank > nifty); // different per-instrument state, not shared
    }
}
