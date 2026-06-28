package com.nifty.analysis.collector.client;

import com.nifty.analysis.dto.OptionSnapshotDto;
import java.util.List;

public interface OptionChainClient {
    /** Fetch the current option chain for the given instrument (e.g. "NIFTY", "BANKNIFTY"). */
    List<OptionSnapshotDto> fetchOptionChain(String instrument);

    /** Back-compat convenience: defaults to NIFTY. */
    default List<OptionSnapshotDto> fetchOptionChain() {
        return fetchOptionChain("NIFTY");
    }
}
