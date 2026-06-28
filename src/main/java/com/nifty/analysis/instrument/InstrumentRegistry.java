package com.nifty.analysis.instrument;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of tradable instruments and their contract specs (P5-2). NIFTY is enabled; BANKNIFTY is
 * registered but DISABLED until the multi-instrument data client + per-instrument collector loop
 * land (slices 2–4) — so adding this registry does not change the running Nifty pipeline.
 *
 * <p>All NSE index options now expire on the same day (Tuesday), handled centrally by
 * {@link com.nifty.analysis.util.TimeUtil}; only strike step and lot size differ per instrument.
 */
@Component
@Slf4j
public class InstrumentRegistry {

    private final Map<String, InstrumentSpec> specs = new LinkedHashMap<>();

    public InstrumentRegistry(@Value("${nifty.instruments.banknifty-enabled:false}") boolean bankNiftyEnabled) {
        register(new InstrumentSpec("NIFTY", 50, 65, true));
        // BANKNIFTY: lot 30, strike step 100, Tuesday expiry. Default OFF so the live Nifty path is
        // never affected; enable via nifty.instruments.banknifty-enabled (e.g. on the simulated provider).
        register(new InstrumentSpec("BANKNIFTY", 100, 30, bankNiftyEnabled));
        log.info("Instrument registry initialised: {} ({} enabled).", specs.keySet(), active().size());
    }

    private void register(InstrumentSpec spec) {
        specs.put(spec.name(), spec);
    }

    public InstrumentSpec get(String name) {
        return specs.get(name);
    }

    /** Instruments the collector pipeline should currently run for. */
    public List<InstrumentSpec> active() {
        return specs.values().stream().filter(InstrumentSpec::enabled).toList();
    }

    public List<InstrumentSpec> all() {
        return List.copyOf(specs.values());
    }
}
