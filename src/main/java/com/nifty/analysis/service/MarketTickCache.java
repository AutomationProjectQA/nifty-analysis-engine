package com.nifty.analysis.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the latest streamed index values (spot / future / VIX). Updated by the
 * Angel One streaming client and read for the real-time portal push.
 */
@Component
public class MarketTickCache {

    private volatile double niftySpot;
    private volatile double niftyFuture;
    private volatile double indiaVix;
    private final AtomicLong lastUpdateMs = new AtomicLong(0);

    public void updateSpot(double v) { niftySpot = v; touch(); }
    public void updateFuture(double v) { niftyFuture = v; touch(); }
    public void updateVix(double v) { indiaVix = v; touch(); }

    private void touch() { lastUpdateMs.set(System.currentTimeMillis()); }

    public double getNiftySpot() { return niftySpot; }
    public double getNiftyFuture() { return niftyFuture; }
    public double getIndiaVix() { return indiaVix; }
    public long getLastUpdateMs() { return lastUpdateMs.get(); }
    public boolean hasData() { return lastUpdateMs.get() > 0; }
}
