package com.nifty.analysis.dto;

import java.time.LocalDateTime;

public record OptionSnapshotDto(
    Integer strikePrice,
    Long ceOi,
    Long peOi,
    Long ceOiChange,
    Long peOiChange,
    Double iv,
    Double pcr,
    Double maxPain,
    Long ceVolume,
    Long peVolume,
    LocalDateTime timestamp
) {}
