package com.nifty.analysis.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of "now" for the engine, pinned to IST (Asia/Kolkata). All stored
 * timestamps (snapshots, signals, candles) and the risk-limit day boundary must use the
 * same zone — otherwise daily trade counts, loss limits, holding times, and expiry symbols
 * are wrong on a server whose local zone is not IST (e.g. a UTC cloud VM).
 *
 * <p><b>Expiry resolution (3 layers, most-trusted first):</b>
 * <ol>
 *   <li><b>Layer 1 — live instrument data:</b> the nearest real contract expiry from the broker
 *       scrip master (set via {@link #configureExpiry}). Correct by construction (holidays/rule
 *       changes are baked into published dates).</li>
 *   <li><b>Layer 2 — holiday-aware weekday:</b> roll to the configured expiry weekday, then move
 *       EARLIER to the previous trading day if that day is an NSE holiday or weekend.</li>
 *   <li><b>Layer 3 — config:</b> the expiry weekday and holiday set are injected at startup
 *       (defaults: Tuesday, no holidays) so a rule change is a config edit, not a redeploy.</li>
 * </ol>
 */
public final class TimeUtil {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * NSE NIFTY default weekly expiry day. Since SEBI's 2025 standardization, NSE indices (NIFTY)
     * expire TUESDAY (BSE/Sensex took Thursday). Was Thursday historically. Overridable via config.
     */
    public static final DayOfWeek DEFAULT_EXPIRY_DAY = DayOfWeek.TUESDAY;

    // --- Startup-configured expiry calendar (Layers 1–3). Volatile: set once at boot, read everywhere. ---
    private static volatile DayOfWeek expiryDay = DEFAULT_EXPIRY_DAY;
    private static volatile Set<LocalDate> holidays = Set.of();
    private static volatile ExpiryInstrumentSource instrumentSource = null;

    private TimeUtil() {}

    public static LocalDateTime nowIst() {
        return ZonedDateTime.now(IST).toLocalDateTime();
    }

    public static LocalDate todayIst() {
        return ZonedDateTime.now(IST).toLocalDate();
    }

    /**
     * Configure the expiry calendar at startup. Any null argument leaves that layer unchanged
     * ({@code source} may be null to disable Layer 1, e.g. for backtests/simulation).
     */
    public static void configureExpiry(DayOfWeek day, Set<LocalDate> holidaySet, ExpiryInstrumentSource source) {
        if (day != null) expiryDay = day;
        if (holidaySet != null) holidays = Set.copyOf(holidaySet);
        instrumentSource = source;
    }

    public static DayOfWeek expiryDay() {
        return expiryDay;
    }

    /** True if {@code date} is a configured NSE trading holiday (excludes weekends — check those separately). */
    public static boolean isExchangeHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    /** Next NIFTY weekly expiry on/after {@code from}: live instrument data if available, else holiday-aware weekday. */
    public static LocalDate nextWeeklyExpiry(LocalDate from) {
        ExpiryInstrumentSource src = instrumentSource;
        if (src != null) {
            Optional<LocalDate> live = src.nearestWeeklyExpiry(from);
            if (live.isPresent()) {
                return live.get(); // Layer 1
            }
        }
        // Layer 2: roll to the expiry weekday, then move earlier off any holiday/weekend. If that
        // makes the expiry fall before `from`, this week's contract already expired → try next week.
        LocalDate anchor = rollForwardToExpiryDay(from);
        LocalDate expiry = adjustEarlierForHolidays(anchor);
        while (expiry.isBefore(from)) {
            anchor = anchor.plusWeeks(1);
            expiry = adjustEarlierForHolidays(anchor);
        }
        return expiry;
    }

    /** Last NIFTY expiry day of {@code date}'s month — the monthly contract expiry (holiday-aware). */
    public static LocalDate lastMonthlyExpiry(LocalDate date) {
        LocalDate d = date.withDayOfMonth(date.lengthOfMonth());
        while (d.getDayOfWeek() != expiryDay) {
            d = d.minusDays(1);
        }
        return adjustEarlierForHolidays(d);
    }

    /** Whole days from {@code from} to the next weekly expiry (0 on expiry day). */
    public static long daysToWeeklyExpiry(LocalDate from) {
        return Math.max(0, ChronoUnit.DAYS.between(from, nextWeeklyExpiry(from)));
    }

    private static LocalDate rollForwardToExpiryDay(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek() != expiryDay) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** NSE moves an expiry that lands on a holiday/weekend EARLIER to the previous trading day. */
    private static LocalDate adjustEarlierForHolidays(LocalDate expiry) {
        LocalDate d = expiry;
        while (isHolidayOrWeekend(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static boolean isHolidayOrWeekend(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY || holidays.contains(d);
    }
}
