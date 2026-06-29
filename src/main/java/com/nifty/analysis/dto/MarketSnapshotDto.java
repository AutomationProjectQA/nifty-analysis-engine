package com.nifty.analysis.dto;

import java.time.LocalDateTime;

public record MarketSnapshotDto(
    Double niftySpot,
    Double niftyFuture,
    Double indiaVix,
    Double volume,
    LocalDateTime timestamp,
    Double dayHigh,
    Double dayLow,
    Double prevClose,
    Double week52High,
    Double week52Low
) {}
