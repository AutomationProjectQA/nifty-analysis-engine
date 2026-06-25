package com.nifty.analysis.service;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
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

    private static final String EXPECTED_INPUT_NAME = "input";
    private static final int EXPECTED_FEATURE_COUNT = 8;

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
                if (!validateSchema()) {
                    log.error("nifty_model.onnx failed schema validation. Disabling model inference (fallback to neutral).");
                    session.close();
                    session = null;
                    return; // isModelLoaded stays false -> callers use rule-based fallback
                }
                isModelLoaded = true;
                log.info("ONNX model loaded and schema-validated. Output nodes: {}", session.getOutputNames());
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
     * @param bbWidth           Bollinger Band Width
     * @param macdHist          MACD Histogram
     * @param volumeRatio       Volume Ratio
     * @return Bullish probability between 0.0 and 100.0
     */
    public double predictBullishProbability(double rsi, double spotToEma20Ratio, double ema20ToEma50Ratio, double vix, double prevDailyReturn, double bbWidth, double macdHist, double volumeRatio) {
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
                    (float) prevDailyReturn,
                    (float) bbWidth,
                    (float) macdHist,
                    (float) volumeRatio
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

    /**
     * Asserts the loaded model matches the contract the serving code relies on:
     * a single input named "input" with {@value #EXPECTED_FEATURE_COUNT} features.
     * Guards against silently feeding a mismatched (e.g. retrained) model wrong-shaped data.
     */
    private boolean validateSchema() {
        try {
            Map<String, NodeInfo> inputInfo = session.getInputInfo();
            if (inputInfo.size() != 1 || !inputInfo.containsKey(EXPECTED_INPUT_NAME)) {
                log.error("ONNX schema mismatch: expected a single input named '{}', found {}",
                        EXPECTED_INPUT_NAME, inputInfo.keySet());
                return false;
            }
            NodeInfo node = inputInfo.get(EXPECTED_INPUT_NAME);
            if (node.getInfo() instanceof TensorInfo tensorInfo) {
                long[] shape = tensorInfo.getShape();
                long featureDim = shape.length > 0 ? shape[shape.length - 1] : -1;
                if (featureDim > 0 && featureDim != EXPECTED_FEATURE_COUNT) {
                    log.error("ONNX schema mismatch: expected {} features, model expects {}",
                            EXPECTED_FEATURE_COUNT, featureDim);
                    return false;
                }
            }
            if (session.getOutputNames().isEmpty()) {
                log.error("ONNX schema mismatch: model has no outputs");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to validate ONNX schema", e);
            return false;
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
