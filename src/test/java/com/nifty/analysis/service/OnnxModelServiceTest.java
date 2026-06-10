package com.nifty.analysis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnxModelServiceTest {

    private OnnxModelService onnxModelService;

    @BeforeEach
    void setUp() {
        onnxModelService = new OnnxModelService();
        onnxModelService.init();
    }

    @Test
    void testPredict() {
        assertTrue(onnxModelService.isModelLoaded(), "Model should be successfully loaded from classpath");
        
        // Execute prediction with typical feature values
        double prob = onnxModelService.predictBullishProbability(55.0, 1.01, 1.02, 13.5, 0.005);
        
        assertTrue(prob >= 0.0 && prob <= 100.0, "Probability should be between 0.0 and 100.0");
        System.out.println("Predicted Bullish Probability: " + prob + "%");
    }
}
