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

import org.springframework.test.util.ReflectionTestUtils;

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
    private com.nifty.analysis.instrument.InstrumentRegistry instrumentRegistry;
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
    private OptionPricingService optionPricingService;
    @Mock
    private OptionPremiumService optionPremiumService;
    @Mock
    private OptionCostService optionCostService;
    @Mock
    private DecisionAgent decisionAgent;
    @Mock
    private TelegramBotService telegramBotService;
    @Mock
    private LlmService llmService;
    @Mock
    private MarketStreamPublisher marketStreamPublisher;
    @Mock
    private com.nifty.analysis.engine.ConfidenceWeightTuner confidenceWeightTuner;

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
                instrumentRegistry,
                tradeSignalRepository,
                tradeResultRepository,
                redisService,
                technicalIndicatorService,
                optionsIndicatorService,
                optionPricingService,
                optionPremiumService,
                optionCostService,
                decisionAgent,
                telegramBotService,
                llmService,
                marketStreamPublisher,
                confidenceWeightTuner
        );
        ReflectionTestUtils.setField(marketCollectorService, "lotSize", 65);
        // Large staleness window so the freshness gate doesn't trip on test timestamps / zone skew.
        ReflectionTestUtils.setField(marketCollectorService, "maxStalenessSeconds", 86400L);
        // Pipeline runs for NIFTY only in tests.
        lenient().when(instrumentRegistry.active()).thenReturn(
                List.of(new com.nifty.analysis.instrument.InstrumentSpec("NIFTY", 50, 65, true)));
        // No theoretical-premium fallback by default; individual tests override as needed.
        lenient().when(optionPremiumService.latestPremiums())
                .thenReturn(new com.nifty.analysis.dto.OptionPremiumDto.Response(0.0, "", 0, List.of()));
        // Zero costs by default so existing assertions are unaffected; cost test overrides.
        lenient().when(optionCostService.roundTripCost(anyDouble(), anyDouble(), anyInt())).thenReturn(0.0);
    }

    @Test
    void testCollectSuccess() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MarketSnapshotDto marketDto = new MarketSnapshotDto(23500.0, 23530.0, 13.5, 100000.0, now);

        OptionSnapshotDto optionDto = new OptionSnapshotDto(23500, 50000L, 60000L, 1000L, 2000L, 12.5, 1.2, 23500.0,
                10000L, 12000L, now);
        List<OptionSnapshotDto> optionDtos = List.of(optionDto);

        when(marketDataClient.fetchMarketData("NIFTY")).thenReturn(marketDto);
        when(optionChainClient.fetchOptionChain("NIFTY")).thenReturn(optionDtos);
        when(technicalIndicatorService.calculateEmaFromCandles(any(String.class), any(String.class), anyInt(), any(LocalDateTime.class), anyDouble())).thenReturn(23500.0);
        when(technicalIndicatorService.calculateRsiFromCandles(any(String.class), any(String.class), anyInt(), any(LocalDateTime.class), anyDouble())).thenReturn(50.0);
        when(technicalIndicatorService.calculateVwap(any(String.class), anyDouble(), anyDouble(), any(LocalDateTime.class))).thenReturn(23500.0);
        when(optionsIndicatorService.calculateMaxPain(anyList())).thenReturn(23500.0);
        when(marketCandleRepository.findLatestByInstrumentAndTimeframe(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(tradeSignalRepository.findByInstrumentAndStatus("NIFTY", "ACTIVE")).thenReturn(List.of());

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
    void asyncDecisions_stillEvaluatesViaExecutor() {
        // With async on, the decision runs on the executor — use a same-thread executor so the
        // verify is deterministic, proving the dispatch path still invokes evaluation exactly once.
        ReflectionTestUtils.setField(marketCollectorService, "asyncDecisions", true);
        ReflectionTestUtils.setField(marketCollectorService, "decisionExecutor",
                (java.util.concurrent.Executor) Runnable::run);

        LocalDateTime now = com.nifty.analysis.util.TimeUtil.nowIst();
        MarketSnapshotDto marketDto = new MarketSnapshotDto(23500.0, 23530.0, 13.5, 100000.0, now);
        when(marketDataClient.fetchMarketData("NIFTY")).thenReturn(marketDto);
        when(optionChainClient.fetchOptionChain("NIFTY")).thenReturn(List.of(
                new OptionSnapshotDto(23500, 50000L, 60000L, 1000L, 2000L, 12.5, 1.2, 23500.0, 10000L, 12000L, now)));
        when(technicalIndicatorService.calculateEmaFromCandles(any(String.class), any(String.class), anyInt(), any(LocalDateTime.class), anyDouble())).thenReturn(23500.0);
        when(technicalIndicatorService.calculateRsiFromCandles(any(String.class), any(String.class), anyInt(), any(LocalDateTime.class), anyDouble())).thenReturn(50.0);
        when(technicalIndicatorService.calculateVwap(any(String.class), anyDouble(), anyDouble(), any(LocalDateTime.class))).thenReturn(23500.0);
        when(optionsIndicatorService.calculateMaxPain(anyList())).thenReturn(23500.0);
        when(marketCandleRepository.findLatestByInstrumentAndTimeframe(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(tradeSignalRepository.findByInstrumentAndStatus("NIFTY", "ACTIVE")).thenReturn(List.of());

        marketCollectorService.collect();

        verify(decisionAgent, times(1)).evaluateMarketForSignals(any(MarketSnapshot.class), any());
    }

    @Test
    void isFeedStale_freshTickPasses() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 23, 11, 0, 30);
        LocalDateTime tick = LocalDateTime.of(2026, 6, 23, 11, 0, 0);   // 30s old
        LocalDateTime prev = LocalDateTime.of(2026, 6, 23, 10, 59, 0);  // strictly older
        assertFalse(MarketCollectorService.isFeedStale(tick, prev, now, 120));
    }

    @Test
    void isFeedStale_tooOldIsStale() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 23, 11, 5, 0);
        LocalDateTime tick = LocalDateTime.of(2026, 6, 23, 11, 0, 0);   // 5 min old > 120s
        assertTrue(MarketCollectorService.isFeedStale(tick, null, now, 120));
    }

    @Test
    void isFeedStale_duplicateOfPrevIsStale() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 23, 11, 0, 5);
        LocalDateTime tick = LocalDateTime.of(2026, 6, 23, 11, 0, 0);
        assertTrue(MarketCollectorService.isFeedStale(tick, tick, now, 120)); // not newer than prev → frozen
    }

    @Test
    void isFeedStale_nullTickIsStale() {
        assertTrue(MarketCollectorService.isFeedStale(null, null, LocalDateTime.now(), 120));
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

        when(tradeSignalRepository.findByInstrumentAndStatus("NIFTY", "ACTIVE")).thenReturn(List.of(activeSignal));

        MarketSnapshot entrySnap = new MarketSnapshot();
        entrySnap.setNiftySpot(23500.0);
        when(marketSnapshotRepository.findLatestBefore(any(LocalDateTime.class))).thenReturn(Optional.of(entrySnap));

        // Live LTP at/above Target2 (180) — resolves via the real market premium.
        when(optionPricingService.getOptionLtp("BUY_CE", 23500)).thenReturn(182.5);

        MarketSnapshot latestSnap = new MarketSnapshot();
        latestSnap.setSnapshotTime(LocalDateTime.now());
        latestSnap.setNiftySpot(23565.0);

        // Act
        marketCollectorService.updateActiveTrades(latestSnap);

        // Assert
        assertEquals("COMPLETED", activeSignal.getStatus());
        verify(tradeSignalRepository, times(1)).save(activeSignal);
        verify(tradeResultRepository, times(1)).save(any(TradeResult.class));
        verify(telegramBotService, times(1)).sendMessage(contains("TARGET 2 HIT"));
    }

    @Test
    void testUpdateActiveTrades_BlackScholesFallbackWhenNoLiveLtp() {
        // No live LTP available → resolution must use the Black-Scholes theoretical premium,
        // NOT the old flat-0.5 proxy.
        TradeSignal activeSignal = new TradeSignal();
        activeSignal.setId(11L);
        activeSignal.setSignalTime(LocalDateTime.now().minusMinutes(5));
        activeSignal.setSignalType("BUY_CE");
        activeSignal.setStrike(23500);
        activeSignal.setEntry(150.0);
        activeSignal.setStopLoss(135.0);
        activeSignal.setTarget1(165.0);
        activeSignal.setTarget2(180.0);
        activeSignal.setStatus("ACTIVE");

        when(tradeSignalRepository.findByInstrumentAndStatus("NIFTY", "ACTIVE")).thenReturn(List.of(activeSignal));
        when(marketSnapshotRepository.findLatestBefore(any(LocalDateTime.class)))
                .thenReturn(Optional.of(new MarketSnapshot()));
        // Live LTP unavailable (sentinel) ...
        when(optionPricingService.getOptionLtp("BUY_CE", 23500)).thenReturn(-1.0);
        // ... so the theoretical premium (185, above Target2 180) drives resolution.
        when(optionPremiumService.latestPremiums()).thenReturn(
                new com.nifty.analysis.dto.OptionPremiumDto.Response(23500.0, "2026-06-25", 2,
                        List.of(new com.nifty.analysis.dto.OptionPremiumDto.StrikePremium(23500, 12.5, 185.0, 5.0))));

        MarketSnapshot latestSnap = new MarketSnapshot();
        latestSnap.setSnapshotTime(LocalDateTime.now());
        latestSnap.setNiftySpot(23560.0);

        marketCollectorService.updateActiveTrades(latestSnap);

        assertEquals("COMPLETED", activeSignal.getStatus());
        verify(tradeResultRepository, times(1)).save(any(TradeResult.class));
    }

    @Test
    void testUpdateActiveTrades_StaysActiveWhenNoPriceAvailable() {
        // Neither a live nor a theoretical price → must NOT resolve on a fabricated price.
        TradeSignal activeSignal = new TradeSignal();
        activeSignal.setId(12L);
        activeSignal.setSignalTime(LocalDateTime.now().minusMinutes(5));
        activeSignal.setSignalType("BUY_CE");
        activeSignal.setStrike(23500);
        activeSignal.setEntry(150.0);
        activeSignal.setStopLoss(135.0);
        activeSignal.setTarget1(165.0);
        activeSignal.setTarget2(180.0);
        activeSignal.setStatus("ACTIVE");

        when(tradeSignalRepository.findByInstrumentAndStatus("NIFTY", "ACTIVE")).thenReturn(List.of(activeSignal));
        when(marketSnapshotRepository.findLatestBefore(any(LocalDateTime.class)))
                .thenReturn(Optional.of(new MarketSnapshot()));
        when(optionPricingService.getOptionLtp("BUY_CE", 23500)).thenReturn(-1.0);
        // latestPremiums returns the empty default (no strike) → no theoretical price.

        MarketSnapshot latestSnap = new MarketSnapshot();
        latestSnap.setSnapshotTime(LocalDateTime.now());
        latestSnap.setNiftySpot(23560.0);

        marketCollectorService.updateActiveTrades(latestSnap);

        assertEquals("ACTIVE", activeSignal.getStatus()); // unresolved, left for next cycle
        verify(tradeResultRepository, never()).save(any(TradeResult.class));
    }

    @Test
    void testUpdateActiveTrades_NetPnlSubtractsCosts() {
        // Recorded P&L must be NET of round-trip transaction costs.
        TradeSignal activeSignal = new TradeSignal();
        activeSignal.setId(13L);
        activeSignal.setSignalTime(LocalDateTime.now().minusMinutes(5));
        activeSignal.setSignalType("BUY_CE");
        activeSignal.setStrike(23500);
        activeSignal.setEntry(150.0);
        activeSignal.setStopLoss(135.0);
        activeSignal.setTarget1(165.0);
        activeSignal.setTarget2(180.0);
        activeSignal.setQuantity(65);
        activeSignal.setStatus("ACTIVE");

        when(tradeSignalRepository.findByInstrumentAndStatus("NIFTY", "ACTIVE")).thenReturn(List.of(activeSignal));
        when(marketSnapshotRepository.findLatestBefore(any(LocalDateTime.class)))
                .thenReturn(Optional.of(new MarketSnapshot()));
        when(optionPricingService.getOptionLtp("BUY_CE", 23500)).thenReturn(182.0); // >= target2 180
        when(optionCostService.roundTripCost(150.0, 180.0, 65)).thenReturn(200.0);

        MarketSnapshot latestSnap = new MarketSnapshot();
        latestSnap.setSnapshotTime(LocalDateTime.now());
        latestSnap.setNiftySpot(23560.0);

        ArgumentCaptor<TradeResult> resultCaptor = ArgumentCaptor.forClass(TradeResult.class);
        marketCollectorService.updateActiveTrades(latestSnap);

        verify(tradeResultRepository).save(resultCaptor.capture());
        // gross = (180-150)*65 = 1950 ; net = 1950 - 200 = 1750
        assertEquals(1750.0, resultCaptor.getValue().getProfitLoss(), 0.01);
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
