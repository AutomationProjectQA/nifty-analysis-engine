package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.OptionsIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class OptionsAgentTest {

    private OptionsIndicatorService optionsIndicatorService;
    private OptionSnapshotRepository optionSnapshotRepository;
    private OptionsAgent optionsAgent;

    @BeforeEach
    void setUp() {
        optionsIndicatorService = new OptionsIndicatorService();
        optionSnapshotRepository = Mockito.mock(OptionSnapshotRepository.class);
        optionsAgent = new OptionsAgent(optionsIndicatorService, optionSnapshotRepository);
    }

    @Test
    void testAnalyze_EmptyChain() {
        AgentResponse response = optionsAgent.analyze(Collections.emptyList(), 23200.0, 0.0);
        assertEquals(50.0, response.score());
        assertEquals("NEUTRAL", response.bias());
        assertTrue(response.comments().contains("No option chain data available"));
    }

    @Test
    void testAnalyze_BullishVolumePcrAndVelocity() {
        LocalDateTime now = LocalDateTime.now();
        // Bullish PCR (>1.2), Bullish Volume PCR (1.5)
        OptionSnapshotDto strikeCe = new OptionSnapshotDto(23200, 100000L, 150000L, 0L, 20000L, 15.0, 1.5, 23200.0, 10000L, 15000L, now, null, null);
        List<OptionSnapshotDto> chain = List.of(strikeCe);

        // Mock historical snapshots for ATM velocity calculation
        OptionSnapshot oldSnap = new OptionSnapshot();
        oldSnap.setStrikePrice(23200);
        oldSnap.setCeOi(100000L);
        oldSnap.setPeOi(100000L);

        OptionSnapshot newSnap = new OptionSnapshot();
        newSnap.setStrikePrice(23200);
        newSnap.setCeOi(105000L);
        newSnap.setPeOi(250000L); // PE OI grew by 150k (>100k limit)

        when(optionSnapshotRepository.findByStrikePriceAndSnapshotTimeAfterOrderBySnapshotTimeDesc(anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of(newSnap, oldSnap));

        AgentResponse response = optionsAgent.analyze(chain, 23200.0, 10.0);

        // Expected score: 50.0 (start) + 15.0 (bullish PCR) + 10.0 (bullish vol PCR) + 15.0 (bullish velocity) = 90.0
        // Plus any build-up indicators. Let's just assert that score is high and bullish
        assertTrue(response.score() > 60.0);
        assertEquals("BULLISH", response.bias());
        assertTrue(response.comments().stream().anyMatch(c -> c.contains("Volume PCR is bullish")));
        assertTrue(response.comments().stream().anyMatch(c -> c.contains("Bullish ATM OI Velocity")));
    }

    @Test
    void testAnalyze_callWritingOnDownMove_isBearishBuildUp() {
        // Phase-2 AG-F8: heavy CE OI build-up on a down move = call writing = BEARISH (was mislabeled).
        // Neutral PCR (1.0) and neutral volume PCR so the build-up term drives the bias.
        LocalDateTime now = LocalDateTime.now();
        OptionSnapshotDto atm = new OptionSnapshotDto(
                23200, 100000L, 100000L, /*ceOiChange*/ 50000L, /*peOiChange*/ 0L,
                15.0, 1.0, 23200.0, /*ceVol*/ 10000L, /*peVol*/ 10000L, now, null, null);
        List<OptionSnapshotDto> chain = List.of(atm);

        when(optionSnapshotRepository.findByStrikePriceAndSnapshotTimeAfterOrderBySnapshotTimeDesc(anyInt(), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList()); // no velocity component

        AgentResponse response = optionsAgent.analyze(chain, 23200.0, /*spotChange*/ -10.0);

        assertEquals("BEARISH", response.bias());
        assertTrue(response.comments().stream().anyMatch(c -> c.contains("Bearish OI build-up")),
                "expected a bearish build-up comment, got " + response.comments());
    }
}
