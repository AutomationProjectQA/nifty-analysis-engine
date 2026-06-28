package com.nifty.analysis.engine;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceCalibratorTest {

    @Test
    void fitLogistic_separableData_isMonotonicAndCalibrated() {
        List<double[]> samples = new ArrayList<>();
        for (int c = 40; c <= 60; c++) samples.add(new double[]{c / 100.0, 0.0}); // low conf → losses
        for (int c = 70; c <= 90; c++) samples.add(new double[]{c / 100.0, 1.0}); // high conf → wins

        double[] ab = ConfidenceCalibrator.fitLogistic(samples, 5000, 0.5);

        assertTrue(ab[0] > 0, "slope should be positive: higher confidence → higher P(win)");
        double pLow = ConfidenceCalibrator.sigmoid(ab[0] * 0.50 + ab[1]);
        double pHigh = ConfidenceCalibrator.sigmoid(ab[0] * 0.85 + ab[1]);
        assertTrue(pHigh > pLow);
        assertTrue(pHigh > 0.5);
        assertTrue(pLow < 0.5);
    }

    @Test
    void breakEvenWinRate_fromRewardRisk() {
        ConfidenceCalibrator c = new ConfidenceCalibrator(null, null);
        ReflectionTestUtils.setField(c, "targetProfitPercent", 2.0);
        ReflectionTestUtils.setField(c, "stopLossPercent", 40.0);
        assertEquals(40.0 / 42.0, c.breakEvenWinRate(), 1e-6); // the "killer" ~95.2%

        ReflectionTestUtils.setField(c, "stopLossPercent", 2.0);
        assertEquals(0.5, c.breakEvenWinRate(), 1e-6); // symmetric 1:1 R:R → 50%
    }

    @Test
    void probabilityOfWin_untrained_returnsSentinel() {
        ConfidenceCalibrator c = new ConfidenceCalibrator(null, null);
        ReflectionTestUtils.setField(c, "enabled", true);
        assertEquals(-1.0, c.probabilityOfWin(80.0), 1e-9); // not trained → unknown
    }
}
