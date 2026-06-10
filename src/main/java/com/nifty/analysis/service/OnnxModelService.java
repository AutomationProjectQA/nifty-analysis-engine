package com.nifty.analysis.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

@Service
@Slf4j
public class OnnxModelService {

    private OrtEnvironment env;
    private OrtSession session;
    private boolean isModelLoaded = false;

    @PostConstruct
    public void init() {
        log.info("Initializing ONNX Model Service and loading nifty_model.onnx...");
        try {
            env = OrtEnvironment.getEnvironment();
            ClassPathResource resource = new ClassPathResource("nifty_model.onnx");
            if (!resource.exists()) {
                log.warn("nifty_model.onnx not found in classpath. Model-based inference will be disabled.");
                return;
            }
            try (InputStream is = resource.getInputStream()) {
                byte[] modelBytes = is.readAllBytes();
                session = env.createSession(modelBytes);
                isModelLoaded = true;
                log.info("ONNX model loaded successfully! Output nodes: {}", session.getOutputNames());
            }
        } catch (Exception e) {
            log.error("Failed to initialize ONNX Runtime or load model", e);
        }
    }

    /**
     * Performs model inference to predict the probability of Nifty moving up (Bullish).
     *
     * @param rsi               RSI (14) value
     * @param spotToEma20Ratio  Nifty Spot / EMA 20
     * @param ema20ToEma50Ratio EMA 20 / EMA 50
     * @param vix               India VIX level
     * @param prevDailyReturn   Previous day's daily return percentage
     * @return Bullish probability between 0.0 and 100.0
     */
    public double predictBullishProbability(double rsi, double spotToEma20Ratio, double ema20ToEma50Ratio, double vix, double prevDailyReturn) {
        if (!isModelLoaded) {
            log.warn("ONNX model not loaded. Returning fallback neutral confidence (50.0%)");
            return 50.0;
        }

        try {
            // Model expects a 2D float array input: [batch_size, num_features]
            float[][] inputData = new float[][] {
                {
                    (float) rsi,
                    (float) spotToEma20Ratio,
                    (float) ema20ToEma50Ratio,
                    (float) vix,
                    (float) prevDailyReturn
                }
            };

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputData)) {
                Map<String, OnnxTensor> inputs = Collections.singletonMap("input", inputTensor);
                
                try (OrtSession.Result results = session.run(inputs)) {
                    // With zipmap: false, output[1] contains a float[][] array of size [batch_size, 2]
                    // representing probabilities for class 0 (down) and class 1 (up)
                    float[][] probabilities = (float[][]) results.get(1).getValue();
                    
                    double bullishProb = probabilities[0][1] * 100.0; // Scale to percentage (0.0 to 100.0)
                    
                    log.debug("ONNX inference complete. Class 0 Prob: {}%, Class 1 (Bullish) Prob: {}%", 
                            probabilities[0][0] * 100.0, bullishProb);
                    
                    return Math.round(bullishProb * 100.0) / 100.0;
                }
            }
        } catch (OrtException e) {
            log.error("ONNX Runtime exception during model prediction", e);
            return 50.0;
        }
    }

    public boolean isModelLoaded() {
        return this.isModelLoaded;
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up ONNX Environment and Session...");
        try {
            if (session != null) {
                session.close();
            }
            if (env != null) {
                env.close();
            }
        } catch (Exception e) {
            log.error("Error cleaning up ONNX components", e);
        }
    }
}
