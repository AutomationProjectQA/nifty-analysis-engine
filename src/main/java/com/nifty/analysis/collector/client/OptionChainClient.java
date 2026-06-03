package com.nifty.analysis.collector.client;

import com.nifty.analysis.dto.OptionSnapshotDto;
import java.util.List;

public interface OptionChainClient {
    List<OptionSnapshotDto> fetchOptionChain();
}
