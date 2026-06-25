package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionPricingServiceTest {

    @Mock private AngelOneDataClient angelOneDataClient;

    @InjectMocks
    private OptionPricingService optionPricingService;

    @Test
    void returnsLtp_whenScripAndPriceAvailable() {
        when(angelOneDataClient.getExpiryDateSymbolStr()).thenReturn("26JUN24");
        when(angelOneDataClient.getScripDetails("NIFTY26JUN2423500CE"))
                .thenReturn(new AngelOneDataClient.ScripTokenDetails("12345", "NIFTY26JUN2423500CE", "NFO"));
        when(angelOneDataClient.fetchLtp("NFO", "12345")).thenReturn(142.5);

        assertEquals(142.5, optionPricingService.getOptionLtp("BUY_CE", 23500), 0.001);
    }

    @Test
    void returnsMinusOne_whenScripMissing() {
        when(angelOneDataClient.getExpiryDateSymbolStr()).thenReturn("26JUN24");
        when(angelOneDataClient.getScripDetails(anyString())).thenReturn(null);

        assertEquals(-1.0, optionPricingService.getOptionLtp("BUY_PE", 23500), 0.001);
    }

    @Test
    void returnsMinusOne_whenLtpNonPositive() {
        when(angelOneDataClient.getExpiryDateSymbolStr()).thenReturn("26JUN24");
        when(angelOneDataClient.getScripDetails(anyString()))
                .thenReturn(new AngelOneDataClient.ScripTokenDetails("12345", "sym", "NFO"));
        lenient().when(angelOneDataClient.fetchLtp("NFO", "12345")).thenReturn(0.0);

        assertEquals(-1.0, optionPricingService.getOptionLtp("BUY_CE", 23500), 0.001);
    }
}
