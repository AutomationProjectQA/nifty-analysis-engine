package com.nifty.analysis.util;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Layer 1 (authoritative) expiry source: the nearest real NIFTY contract expiry, read from
 * live broker instrument data (the scrip master). Because the exchange bakes holiday shifts and
 * rule changes into the published contract dates, this is correct by construction — preferred
 * over any weekday calculation when available.
 */
public interface ExpiryInstrumentSource {

    /** Nearest NIFTY weekly contract expiry on/after {@code from}, if instrument data is loaded. */
    Optional<LocalDate> nearestWeeklyExpiry(LocalDate from);
}
