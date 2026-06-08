package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CriticAgentTest {

    @Mock
    private EventRiskAgent eventRiskAgent;

    private CriticAgent criticAgent;

    @BeforeEach
    void setUp() {
        criticAgent = new CriticAgent(eventRiskAgent);
    }

    @Test
    void testEvaluateAndApplyPenalties_CallResistanceWall() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MarketSnapshot latest = new MarketSnapshot();
        latest.setSnapshotTime(now);
        latest.setRsi(55.0);
        latest.setIndiaVix(12.0);
        latest.setNiftySpot(23500.0);

        when(eventRiskAgent.evaluateCurrentRisk(now.toLocalDate()))
                .thenReturn(new AgentResponse(0.0, "NEUTRAL", List.of()));

        // Options chain with CE wall at 23550 (atmStrike + 50)
        List<OptionSnapshot> optionChain = new ArrayList<>();
        OptionSnapshot wallStrike = new OptionSnapshot();
        wallStrike.setStrikePrice(23550);
        wallStrike.setCeOi(1000000L); // High call writing
        wallStrike.setPeOi(300000L);  // Low put writing
        wallStrike.setCeOiChange(10000L);
        wallStrike.setPeOiChange(5000L);
        optionChain.add(wallStrike);

        // Act
        CriticAgent.CriticResult result = criticAgent.evaluateAndApplyPenalties(80.0, latest, optionChain, true);

        // Assert
        // Starting: 80.0. Expect penalty of 15.0 for OI Resistance Wall.
        assertEquals(65.0, result.adjustedConfidence());
        assertTrue(result.appliedPenalties().stream()
                .anyMatch(p -> "OI Resistance Wall".equals(p.factor())));
    }

    @Test
    void testEvaluateAndApplyPenalties_PutSupportWall() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MarketSnapshot latest = new MarketSnapshot();
        latest.setSnapshotTime(now);
        latest.setRsi(55.0);
        latest.setIndiaVix(12.0);
        latest.setNiftySpot(23500.0);

        when(eventRiskAgent.evaluateCurrentRisk(now.toLocalDate()))
                .thenReturn(new AgentResponse(0.0, "NEUTRAL", List.of()));

        // Options chain with PE wall at 23450 (atmStrike - 50)
        List<OptionSnapshot> optionChain = new ArrayList<>();
        OptionSnapshot wallStrike = new OptionSnapshot();
        wallStrike.setStrikePrice(23450);
        wallStrike.setCeOi(200000L);  // Low call writing
        wallStrike.setPeOi(800000L);  // High put writing
        wallStrike.setCeOiChange(5000L);
        wallStrike.setPeOiChange(15000L);
        optionChain.add(wallStrike);

        // Act
        CriticAgent.CriticResult result = criticAgent.evaluateAndApplyPenalties(80.0, latest, optionChain, false);

        // Assert
        // Starting: 80.0. Expect penalty of 15.0 for OI Support Wall.
        assertEquals(65.0, result.adjustedConfidence());
        assertTrue(result.appliedPenalties().stream()
                .anyMatch(p -> "OI Support Wall".equals(p.factor())));
    }
}
