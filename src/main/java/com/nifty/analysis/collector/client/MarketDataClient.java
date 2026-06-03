package com.nifty.analysis.collector.client;

import com.nifty.analysis.dto.MarketSnapshotDto;

public interface MarketDataClient {
    MarketSnapshotDto fetchMarketData();
}
