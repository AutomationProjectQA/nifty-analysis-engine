package com.nifty.analysis.service;

import com.nifty.analysis.collector.client.MarketDataClient;
import com.nifty.analysis.collector.client.OptionChainClient;
import com.nifty.analysis.dto.MarketSnapshotDto;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketCandleRepository;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.agent.DecisionAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketCollectorServiceTest {

    @Mock
    private MarketDataClient marketDataClient;
    @Mock
    private OptionChainClient optionChainClient;
    @Mock
    private MarketSnapshotRepository marketSnapshotRepository;
    @Mock
    private OptionSnapshotRepository optionSnapshotRepository;
    @Mock
    private MarketCandleRepository marketCandleRepository;
    @Mock
    private RedisService redisService;
    @Mock
    private TechnicalIndicatorService technicalIndicatorService;
    @Mock
    private OptionsIndicatorService optionsIndicatorService;
    @Mock
    private DecisionAgent decisionAgent;

    private MarketCollectorService marketCollectorService;

    @Captor
    private ArgumentCaptor<MarketSnapshot> marketSnapshotCaptor;
    @Captor
    private ArgumentCaptor<List<OptionSnapshot>> optionChainCaptor;

    @BeforeEach
    void setUp() {
        marketCollectorService = new MarketCollectorService(
                marketDataClient,
                optionChainClient,
                marketSnapshotRepository,
                optionSnapshotRepository,
                marketCandleRepository,
                redisService,
                technicalIndicatorService,
                optionsIndicatorService,
                decisionAgent
        );
    }

    @Test
    void testCollectSuccess() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MarketSnapshotDto marketDto = new MarketSnapshotDto(23500.0, 23530.0, 13.5, 100000.0, now);

        OptionSnapshotDto optionDto = new OptionSnapshotDto(23500, 50000L, 60000L, 1000L, 2000L, 12.5, 1.2, 23500.0,
                now);
        List<OptionSnapshotDto> optionDtos = List.of(optionDto);

        when(marketDataClient.fetchMarketData()).thenReturn(marketDto);
        when(optionChainClient.fetchOptionChain()).thenReturn(optionDtos);
        when(technicalIndicatorService.calculateEma(anyDouble(), any(), anyInt())).thenReturn(23500.0);
        when(technicalIndicatorService.calculateRsi(anyDouble(), any(LocalDateTime.class))).thenReturn(50.0);
        when(technicalIndicatorService.calculateVwap(anyDouble(), anyDouble(), any(LocalDateTime.class))).thenReturn(23500.0);
        when(optionsIndicatorService.calculateMaxPain(anyList())).thenReturn(23500.0);
        when(marketCandleRepository.findLatestByTimeframe(anyString(), anyInt())).thenReturn(List.of());

        // Act
        marketCollectorService.collect();

        // Assert
        // Verify market snapshot persistence and cache calls
        verify(marketSnapshotRepository, times(1)).save(marketSnapshotCaptor.capture());
        MarketSnapshot savedSnapshot = marketSnapshotCaptor.getValue();
        assertEquals(23500.0, savedSnapshot.getNiftySpot());
        assertEquals(23530.0, savedSnapshot.getNiftyFuture());
        assertEquals(13.5, savedSnapshot.getIndiaVix());
        assertEquals(100000.0, savedSnapshot.getVolume());
        verify(redisService, times(1)).saveLatestMarketSnapshot(savedSnapshot);

        // Verify option chain persistence and cache calls
        verify(optionSnapshotRepository, times(1)).saveAll(optionChainCaptor.capture());
        List<OptionSnapshot> savedOptions = optionChainCaptor.getValue();
        assertEquals(1, savedOptions.size());
        OptionSnapshot savedOption = savedOptions.getFirst();
        assertEquals(23500, savedOption.getStrikePrice());
        assertEquals(50000L, savedOption.getCeOi());
        assertEquals(60000L, savedOption.getPeOi());
        assertEquals(1.2, savedOption.getPcr());
        verify(redisService, times(1)).saveLatestOptionChain(savedOptions);
        
        // Verify decision agent signal checks trigger at end of collection
        verify(decisionAgent, times(1)).evaluateMarketForSignals(any(MarketSnapshot.class), any());
    }
}
