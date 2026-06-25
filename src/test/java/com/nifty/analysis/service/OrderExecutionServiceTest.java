package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.impl.AngelOneDataClient;
import com.nifty.analysis.notification.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

    @Mock
    private AngelOneDataClient angelOneDataClient;

    @Mock
    private TelegramBotService telegramBotService;

    @Mock
    private WebClient.Builder webClientBuilder;

    private OrderExecutionService orderExecutionService;

    @BeforeEach
    void setUp() {
        orderExecutionService = new OrderExecutionService(angelOneDataClient, webClientBuilder, telegramBotService);
        ReflectionTestUtils.setField(orderExecutionService, "enabled", true);
        ReflectionTestUtils.setField(orderExecutionService, "lotSize", 65);
        ReflectionTestUtils.setField(orderExecutionService, "capitalPerOrderPercent", 100.0);
    }

    @Test
    void testExecuteOrder_Disabled() {
        // Arrange
        ReflectionTestUtils.setField(orderExecutionService, "enabled", false);

        // Act
        orderExecutionService.executeOrder("BUY_CE", 23500, 23500.0);

        // Assert
        verifyNoInteractions(angelOneDataClient);
        verifyNoInteractions(telegramBotService);
    }

    @Test
    void testExecuteOrder_SimulationMode() {
        // Arrange
        when(angelOneDataClient.getExpiryDateSymbolStr()).thenReturn("09JUN26");
        String symbol = "NIFTY09JUN2623500CE";
        AngelOneDataClient.ScripTokenDetails details = new AngelOneDataClient.ScripTokenDetails("12345", symbol, "NFO");
        when(angelOneDataClient.getScripDetails(symbol)).thenReturn(details);
        when(angelOneDataClient.fetchLtp("NFO", "12345")).thenReturn(100.0);
        when(angelOneDataClient.getJwtToken()).thenReturn("SIMULATED_JWT_TOKEN");

        // Act
        orderExecutionService.executeOrder("BUY_CE", 23500, 23500.0);

        // Assert
        verify(telegramBotService, times(1)).sendMessage(argThat(msg -> msg != null && msg.contains("SIMULATED ROBO ORDER PLACED")));
        verify(telegramBotService, times(1)).sendMessage(argThat(msg -> msg != null && msg.contains("Lots") && msg.contains("7"))); // 50000 / (100 * 65) = 7.69 => 7 lots
    }
}
