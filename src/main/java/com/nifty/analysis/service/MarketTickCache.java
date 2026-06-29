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

    // Trusted reference from the per-cycle REST collector. The streaming client validates each
    // binary tick against this so a misparsed frame offset can never push garbage to the UI.
    private volatile double referenceSpot;
    private volatile double referenceFuture;
    private volatile double referenceVix;
    private volatile boolean hasReference = false;

    public void updateSpot(double v) { niftySpot = v; touch(); }
    public void updateFuture(double v) { niftyFuture = v; touch(); }
    public void updateVix(double v) { indiaVix = v; touch(); }

    private void touch() { lastUpdateMs.set(System.currentTimeMillis()); }

    public double getNiftySpot() { return niftySpot; }
    public double getNiftyFuture() { return niftyFuture; }
    public double getIndiaVix() { return indiaVix; }
    public long getLastUpdateMs() { return lastUpdateMs.get(); }
    public boolean hasData() { return lastUpdateMs.get() > 0; }

    /** Seeds the trusted reference values from a verified REST snapshot (NIFTY only). */
    public void setReference(double spot, double future, double vix) {
        if (spot > 0) referenceSpot = spot;
        if (future > 0) referenceFuture = future;
        if (vix > 0) referenceVix = vix;
        hasReference = true;
    }

    public double getReferenceSpot() { return referenceSpot; }
    public double getReferenceFuture() { return referenceFuture; }
    public double getReferenceVix() { return referenceVix; }
    public boolean hasReference() { return hasReference; }
}
