package com.nifty.analysis.service;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.notification.TelegramBotService;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.repository.TradeSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Verifies the data-health verdict flags each critical degraded state (the "fail loud" guarantee). */
@ExtendWith(MockitoExtension.class)
class DataHealthServiceTest {

    @Mock private DataFeedStatus dataFeedStatus;
    @Mock private MarketSnapshotRepository marketSnapshotRepository;
    @Mock private OptionSnapshotRepository optionSnapshotRepository;
    @Mock private TradeSignalRepository tradeSignalRepository;
    @Mock private TelegramBotService telegramBotService;

    private DataHealthService service;

    @BeforeEach
    void setUp() {
        service = new DataHealthService(dataFeedStatus, marketSnapshotRepository,
                optionSnapshotRepository, tradeSignalRepository, telegramBotService);
        ReflectionTestUtils.setField(service, "provider", "angelone");
        ReflectionTestUtils.setField(service, "geminiApiKey", "key-present");
        ReflectionTestUtils.setField(service, "spotMin", 15000.0);
        ReflectionTestUtils.setField(service, "spotMax", 50000.0);
        ReflectionTestUtils.setField(service, "maxStaleSeconds", 300L);
        ReflectionTestUtils.setField(service, "alertEnabled", false);

        // Default: everything healthy + fresh, with a signal today (neutralises time-gated checks).
        LocalDateTime now = LocalDateTime.now();
        lenient().when(dataFeedStatus.isLive("NIFTY")).thenReturn(true);
        MarketSnapshot snap = new MarketSnapshot();
        snap.setInstrument("NIFTY");
        snap.setNiftySpot(24050.0);
        snap.setSnapshotTime(now);
        lenient().when(marketSnapshotRepository.findLatestByInstrument("NIFTY")).thenReturn(Optional.of(snap));
        lenient().when(optionSnapshotRepository.findLatestSnapshotTimeByInstrument("NIFTY")).thenReturn(now);
        TradeSignal sig = new TradeSignal();
        sig.setSignalTime(now);
        lenient().when(tradeSignalRepository.findAllByOrderBySignalTimeDesc()).thenReturn(List.of(sig));
    }

    @Test
    void allGood_isHealthy() {
        DataHealthService.Report r = service.report();
        assertEquals("HEALTHY", r.status(), "problems: " + r.problems());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void simulatedFeed_isDegraded() {
        when(dataFeedStatus.isLive("NIFTY")).thenReturn(false);
        DataHealthService.Report r = service.report();
        assertEquals("DEGRADED", r.status());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("SIMULATED")));
    }

    @Test
    void insaneSpot_isDegraded() {
        MarketSnapshot snap = new MarketSnapshot();
        snap.setInstrument("NIFTY");
        snap.setNiftySpot(60000.0); // out of [15000,50000]
        snap.setSnapshotTime(LocalDateTime.now());
        when(marketSnapshotRepository.findLatestByInstrument("NIFTY")).thenReturn(Optional.of(snap));

        DataHealthService.Report r = service.report();
        assertEquals("DEGRADED", r.status());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("outside sane band")));
    }

    @Test
    void blankGeminiKey_isDegraded() {
        ReflectionTestUtils.setField(service, "geminiApiKey", "");
        DataHealthService.Report r = service.report();
        assertEquals("DEGRADED", r.status());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("GEMINI_API_KEY")));
    }

    @Test
    void noSnapshot_isDegraded() {
        when(marketSnapshotRepository.findLatestByInstrument("NIFTY")).thenReturn(Optional.empty());
        DataHealthService.Report r = service.report();
        assertEquals("DEGRADED", r.status());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("No NIFTY market snapshot")));
    }
}
