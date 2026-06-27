package com.nifty.analysis.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Single source of "now" for the engine, pinned to IST (Asia/Kolkata). All stored
 * timestamps (snapshots, signals, candles) and the risk-limit day boundary must use the
 * same zone — otherwise daily trade counts, loss limits, holding times, and expiry symbols
 * are wrong on a server whose local zone is not IST (e.g. a UTC cloud VM).
 */
public final class TimeUtil {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TimeUtil() {}

    public static LocalDateTime nowIst() {
        return ZonedDateTime.now(IST).toLocalDateTime();
    }

    public static LocalDate todayIst() {
        return ZonedDateTime.now(IST).toLocalDate();
    }
}
