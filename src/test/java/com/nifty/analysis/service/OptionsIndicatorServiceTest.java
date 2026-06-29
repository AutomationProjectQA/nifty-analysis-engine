package com.nifty.analysis.service;

import com.nifty.analysis.dto.OptionSnapshotDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OptionsIndicatorServiceTest {

    private OptionsIndicatorService optionsIndicatorService;

    @BeforeEach
    void setUp() {
        optionsIndicatorService = new OptionsIndicatorService();
    }

    @Test
    void testCalculateOverallPcr() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        OptionSnapshotDto s1 = new OptionSnapshotDto(23500, 100000L, 120000L, 0L, 0L, 12.0, 1.2, 23500.0, 15000L, 18000L, now, null, null);
        OptionSnapshotDto s2 = new OptionSnapshotDto(23550, 200000L, 150000L, 0L, 0L, 12.0, 0.75, 23500.0, 25000L, 18000L, now, null, null);
        
        List<OptionSnapshotDto> chain = List.of(s1, s2);

        // Act
        double pcr = optionsIndicatorService.calculateOverallPcr(chain);

        // Assert
        // Total Call OI = 100k + 200k = 300k
        // Total Put OI = 120k + 150k = 270k
        // PCR = 270k / 300k = 0.9
        assertEquals(0.9, pcr);
    }

    @Test
    void testCalculateVolumePcr() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        OptionSnapshotDto s1 = new OptionSnapshotDto(23500, 100000L, 120000L, 0L, 0L, 12.0, 1.2, 23500.0, 10000L, 15000L, now, null, null);
        OptionSnapshotDto s2 = new OptionSnapshotDto(23550, 200000L, 150000L, 0L, 0L, 12.0, 0.75, 23500.0, 30000L, 15000L, now, null, null);
        
        List<OptionSnapshotDto> chain = List.of(s1, s2);

        // Act
        double volPcr = optionsIndicatorService.calculateVolumePcr(chain);

        // Assert
        // Total Call Vol = 10k + 30k = 40k
        // Total Put Vol = 15k + 15k = 30k
        // Vol PCR = 30k / 40k = 0.75
        assertEquals(0.75, volPcr);
    }

    @Test
    void testCalculateMaxPain() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        // Option chain with three strikes
        OptionSnapshotDto s1 = new OptionSnapshotDto(23400, 10000L, 50000L, 0L, 0L, 12.0, 5.0, 23400.0, 1000L, 5000L, now, null, null);
        OptionSnapshotDto s2 = new OptionSnapshotDto(23500, 80000L, 70000L, 0L, 0L, 12.0, 0.875, 23500.0, 8000L, 7000L, now, null, null);
        OptionSnapshotDto s3 = new OptionSnapshotDto(23600, 90000L, 10000L, 0L, 0L, 12.0, 0.11, 23600.0, 9000L, 1000L, now, null, null);
        
        List<OptionSnapshotDto> chain = List.of(s1, s2, s3);

        // Act
        double maxPain = optionsIndicatorService.calculateMaxPain(chain);

        // Assert
        // The strike price minimizing writer pain should be 23500 (since it has high Call and Put OI)
        assertEquals(23500.0, maxPain);
    }

    @Test
    void testDetectBuildUpCalls() {
        // Spot rise (+), OI rise (+) -> Long Build-up
        assertEquals(OptionsIndicatorService.BuildUpType.LONG_BUILD_UP, 
                optionsIndicatorService.detectBuildUp(true, 10.0, 5000L));
        
        // Spot rise (+), OI fall (-) -> Short Covering
        assertEquals(OptionsIndicatorService.BuildUpType.SHORT_COVERING, 
                optionsIndicatorService.detectBuildUp(true, 10.0, -2000L));
        
        // Spot fall (-), OI rise (+) -> Short Build-up
        assertEquals(OptionsIndicatorService.BuildUpType.SHORT_BUILD_UP, 
                optionsIndicatorService.detectBuildUp(true, -5.0, 1000L));
    }

    @Test
    void testDetectBuildUpPuts() {
        // Spot fall (-), OI rise (+) -> Long Build-up for Put Option (Option price rises when spot falls)
        assertEquals(OptionsIndicatorService.BuildUpType.LONG_BUILD_UP, 
                optionsIndicatorService.detectBuildUp(false, -10.0, 5000L));
        
        // Spot rise (+), OI rise (+) -> Short Build-up for Put Option (Option price falls when spot rises)
        assertEquals(OptionsIndicatorService.BuildUpType.SHORT_BUILD_UP, 
                optionsIndicatorService.detectBuildUp(false, 5.0, 10000L));
    }
}
