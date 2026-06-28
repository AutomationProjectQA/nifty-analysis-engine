package com.nifty.analysis.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilTest {

    @AfterEach
    void resetCalendar() {
        // TimeUtil's expiry calendar is process-global; reset to defaults so tests don't leak state.
        TimeUtil.configureExpiry(TimeUtil.DEFAULT_EXPIRY_DAY, Set.of(), null);
    }

    @Test
    void nextWeeklyExpiry_isTuesdayOrSameDay() {
        assertEquals(LocalDate.of(2026, 6, 23), TimeUtil.nextWeeklyExpiry(LocalDate.of(2026, 6, 23))); // Tue → itself
        assertEquals(LocalDate.of(2026, 6, 23), TimeUtil.nextWeeklyExpiry(LocalDate.of(2026, 6, 22))); // Mon → that Tue
    }

    @Test
    void daysToWeeklyExpiry_countsWholeDays() {
        assertEquals(0, TimeUtil.daysToWeeklyExpiry(LocalDate.of(2026, 6, 23))); // expiry day (Tue)
        assertEquals(3, TimeUtil.daysToWeeklyExpiry(LocalDate.of(2026, 6, 20))); // Sat → Tue
    }

    @Test
    void lastMonthlyExpiry_isLastTuesdayOfMonth() {
        assertEquals(LocalDate.of(2026, 6, 30), TimeUtil.lastMonthlyExpiry(LocalDate.of(2026, 6, 15))); // last Tue of Jun 2026
    }

    @Test
    void layer1_liveInstrumentSourceWins() {
        TimeUtil.configureExpiry(DayOfWeek.TUESDAY, Set.of(), from -> Optional.of(LocalDate.of(2026, 7, 1)));
        assertEquals(LocalDate.of(2026, 7, 1), TimeUtil.nextWeeklyExpiry(LocalDate.of(2026, 6, 20)));
    }

    @Test
    void layer2_holidayMovesExpiryToPreviousTradingDay() {
        // Tuesday 2026-06-23 is a holiday → expiry moves EARLIER to Monday 2026-06-22.
        TimeUtil.configureExpiry(DayOfWeek.TUESDAY, Set.of(LocalDate.of(2026, 6, 23)), null);
        assertEquals(LocalDate.of(2026, 6, 22), TimeUtil.nextWeeklyExpiry(LocalDate.of(2026, 6, 18)));
    }

    @Test
    void layer2_ifAdjustedExpiryAlreadyPassed_rollsToNextWeek() {
        // Asking on the holiday-Tuesday itself: that week's contract expired Mon → next is 2026-06-30.
        TimeUtil.configureExpiry(DayOfWeek.TUESDAY, Set.of(LocalDate.of(2026, 6, 23)), null);
        assertEquals(LocalDate.of(2026, 6, 30), TimeUtil.nextWeeklyExpiry(LocalDate.of(2026, 6, 23)));
    }

    @Test
    void layer3_configurableExpiryDay() {
        TimeUtil.configureExpiry(DayOfWeek.THURSDAY, Set.of(), null);
        assertEquals(LocalDate.of(2026, 6, 25), TimeUtil.nextWeeklyExpiry(LocalDate.of(2026, 6, 23))); // Tue → next Thu
    }

    @Test
    void isExchangeHoliday_reflectsConfiguredSet() {
        TimeUtil.configureExpiry(DayOfWeek.TUESDAY, Set.of(LocalDate.of(2026, 1, 26)), null);
        assertTrue(TimeUtil.isExchangeHoliday(LocalDate.of(2026, 1, 26)));   // Republic Day
        assertFalse(TimeUtil.isExchangeHoliday(LocalDate.of(2026, 1, 27)));
    }
}
