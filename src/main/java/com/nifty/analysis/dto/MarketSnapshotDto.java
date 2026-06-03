package com.nifty.analysis.dto;

import java.time.LocalDateTime;

public record MarketSnapshotDto(
    Double niftySpot,
    Double niftyFuture,
    Double indiaVix,
    Double volume,
    LocalDateTime timestamp
) {}
