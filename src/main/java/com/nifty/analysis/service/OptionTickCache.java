package com.nifty.analysis.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the latest streamed per-strike option data (OI + LTP for CE/PE), updated by the
 * Angel One SnapQuote stream and read for the real-time option-chain push to the portal.
 */
@Component
public class OptionTickCache {

    private static final class Strike {
        volatile long ceOi;
        volatile long peOi;
        volatile double ceLtp;
        volatile double peLtp;
    }

    private final Map<Integer, Strike> byStrike = new ConcurrentHashMap<>();

    public void updateCe(int strike, long oi, double ltp) {
        Strike s = byStrike.computeIfAbsent(strike, k -> new Strike());
        s.ceOi = oi;
        s.ceLtp = ltp;
    }

    public void updatePe(int strike, long oi, double ltp) {
        Strike s = byStrike.computeIfAbsent(strike, k -> new Strike());
        s.peOi = oi;
        s.peLtp = ltp;
    }

    /** Snapshot of all strikes (sorted), as plain maps ready for JSON push to /topic/optionsTick. */
    public List<Map<String, Object>> snapshot() {
        List<Integer> strikes = new ArrayList<>(byStrike.keySet());
        strikes.sort(Integer::compareTo);
        List<Map<String, Object>> out = new ArrayList<>(strikes.size());
        for (int strike : strikes) {
            Strike s = byStrike.get(strike);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strikePrice", strike);
            row.put("ceOi", s.ceOi);
            row.put("peOi", s.peOi);
            row.put("ceLtp", s.ceLtp);
            row.put("peLtp", s.peLtp);
            out.add(row);
        }
        return out;
    }

    public boolean isEmpty() {
        return byStrike.isEmpty();
    }
}
