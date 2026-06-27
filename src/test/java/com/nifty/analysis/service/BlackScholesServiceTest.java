package com.nifty.analysis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackScholesServiceTest {

    private final BlackScholesService bs = new BlackScholesService();

    @Test
    void impliedVol_recoversTheInputVolForACall() {
        double spot = 23500, strike = 23500, years = 7.0 / 365.0, iv = 15.0;
        double price = bs.price(spot, strike, iv, years, true);
        double solved = bs.impliedVol(spot, strike, years, true, price);
        assertEquals(iv, solved, 0.5); // within half a vol point
    }

    @Test
    void impliedVol_recoversTheInputVolForAPut() {
        double spot = 23500, strike = 23400, years = 14.0 / 365.0, iv = 18.0;
        double price = bs.price(spot, strike, iv, years, false);
        double solved = bs.impliedVol(spot, strike, years, false, price);
        assertEquals(iv, solved, 0.5);
    }

    @Test
    void impliedVol_returnsSentinelWhenPriceBelowIntrinsic() {
        // Deep ITM call priced below its intrinsic value cannot be inverted.
        double solved = bs.impliedVol(24000, 23000, 7.0 / 365.0, true, 100.0); // intrinsic = 1000
        assertTrue(solved < 0);
    }

    @Test
    void price_isPositiveAndAboveIntrinsicForAtm() {
        double price = bs.price(23500, 23500, 12.5, 7.0 / 365.0, true);
        assertTrue(price > 0);
    }
}
