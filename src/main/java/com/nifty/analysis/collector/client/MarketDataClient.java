package com.nifty.analysis.collector.client;

import com.nifty.analysis.dto.MarketSnapshotDto;

public interface MarketDataClient {
    /** Fetch the latest index/future/VIX snapshot for the given instrument (e.g. "NIFTY", "BANKNIFTY"). */
    MarketSnapshotDto fetchMarketData(String instrument);

    /** Back-compat convenience: defaults to NIFTY. */
    default MarketSnapshotDto fetchMarketData() {
        return fetchMarketData("NIFTY");
    }
}
