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
import com.nifty.analysis.repository.TradeSignalRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.notification.TelegramBotService;
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
import java.util.Optional;

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
    private TradeSignalRepository tradeSignalRepository;
    @Mock
    private TradeResultRepository tradeResultRepository;
    @Mock
    private RedisService redisService;
    @Mock
    private TechnicalIndicatorService technicalIndicatorService;
    @Mock
    private OptionsIndicatorService optionsIndicatorService;
    @Mock
    private DecisionAgent decisionAgent;
    @Mock
    private TelegramBotService telegramBotService;
    @Mock
    private LlmService llmService;

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
                tradeSignalRepository,
                tradeResultRepository,
                redisService,
                technicalIndicatorService,
                optionsIndicatorService,
                decisionAgent,
                telegramBotService,
                llmService
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
        when(tradeSignalRepository.findByStatus("ACTIVE")).thenReturn(List.of());

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

    @Test
    void testUpdateActiveTrades_TargetHit() {
        // Arrange
        TradeSignal activeSignal = new TradeSignal();
        activeSignal.setId(10L);
        activeSignal.setSignalTime(LocalDateTime.now().minusMinutes(5));
        activeSignal.setSignalType("BUY_CE");
        activeSignal.setStrike(23500);
        activeSignal.setEntry(150.0);
        activeSignal.setStopLoss(135.0);
        activeSignal.setTarget1(165.0);
        activeSignal.setTarget2(180.0);
        activeSignal.setStatus("ACTIVE");

        when(tradeSignalRepository.findByStatus("ACTIVE")).thenReturn(List.of(activeSignal));

        MarketSnapshot entrySnap = new MarketSnapshot();
        entrySnap.setNiftySpot(23500.0);
        when(marketSnapshotRepository.findLatestBefore(any(LocalDateTime.class))).thenReturn(Optional.of(entrySnap));

        MarketSnapshot latestSnap = new MarketSnapshot();
        latestSnap.setSnapshotTime(LocalDateTime.now());
        latestSnap.setNiftySpot(23565.0); // 150 + 65*0.5 = 182.5 (hits target2 >= 180.0)

        // Act
        marketCollectorService.updateActiveTrades(latestSnap);

        // Assert
        assertEquals("COMPLETED", activeSignal.getStatus());
        verify(tradeSignalRepository, times(1)).save(activeSignal);
        verify(tradeResultRepository, times(1)).save(any(TradeResult.class));
        verify(telegramBotService, times(1)).sendMessage(contains("TARGET 2 HIT"));
    }

    @Test
    void testSend30MinSummary_Success() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MarketSnapshot snap1 = new MarketSnapshot();
        snap1.setSnapshotTime(now.minusMinutes(20));
        snap1.setNiftySpot(23500.0);
        snap1.setRsi(55.0);
        snap1.setVwap(23490.0);
        snap1.setEma20(23480.0);
        snap1.setEma50(23470.0);

        MarketSnapshot snap2 = new MarketSnapshot();
        snap2.setSnapshotTime(now.minusMinutes(10));
        snap2.setNiftySpot(23580.0);
        snap2.setRsi(65.0);
        snap2.setVwap(23510.0);
        snap2.setEma20(23490.0);
        snap2.setEma50(23475.0);

        MarketSnapshot snap3 = new MarketSnapshot();
        snap3.setSnapshotTime(now);
        snap3.setNiftySpot(23520.0);
        snap3.setRsi(60.0);
        snap3.setVwap(23515.0);
        snap3.setEma20(23500.0);
        snap3.setEma50(23480.0);

        when(marketSnapshotRepository.findBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(snap1, snap2, snap3));
        when(tradeSignalRepository.findByStatus("ACTIVE")).thenReturn(List.of());
        when(tradeSignalRepository.findBySignalTimeAfter(any(LocalDateTime.class))).thenReturn(List.of());
        when(llmService.generateMarketSummary(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any(), any(), any(), any()))
                .thenReturn("Test AI Market Summary Output.");

        // Act
        marketCollectorService.send30MinSummary();

        // Assert
        verify(telegramBotService, times(1)).sendMessage(contains("NIFTY 30-MINUTE MARKET UPDATE"));
        verify(telegramBotService, times(1)).sendMessage(contains("Test AI Market Summary Output."));
    }
}
