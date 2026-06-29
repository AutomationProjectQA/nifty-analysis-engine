package com.nifty.analysis.service;

import com.nifty.analysis.engine.ConfidenceCalibrator;
import com.nifty.analysis.entity.SignalExplanation;
import com.nifty.analysis.entity.TradeResult;
import com.nifty.analysis.repository.SignalExplanationRepository;
import com.nifty.analysis.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase-4 adaptive-AI observability (audit #209 / T18-2): measures whether the engine's confidence
 * is CALIBRATED — i.e. does "predicted 80%" actually win ~80% of the time? Buckets resolved trades
 * by the confidence the gate saw ({@code Final_Confidence}) and reports the realised win-rate per
 * bucket, alongside the calibrator's modelled probability. Without this, confidence silently drifts
 * from meaning over time. Read-only; never affects the decision path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalibrationMonitorService {

    private static final String CALIBRATION_FACTOR = "Final_Confidence";
    // Confidence-bucket edges (%). A trade lands in [edge[i], edge[i+1]).
    private static final double[] EDGES = {0, 50, 60, 70, 80, 90, 100.0001};

    private final TradeResultRepository tradeResultRepository;
    private final SignalExplanationRepository signalExplanationRepository;
    private final ConfidenceCalibrator calibrator;

    /** One confidence band and how trades in it actually resolved. */
    public record Bucket(String label, double midConfidence, int count, int wins,
                         double actualWinRate, Double modelPredictedWinRate) {}

    /** Full calibration report: per-bucket reliability + overall + calibrator state. */
    public record Report(boolean calibratorTrained, int calibratorSamples, double breakEvenWinRate,
                         int totalResolved, double overallWinRate, List<Bucket> buckets) {}

    public Report report() {
        List<TradeResult> results = tradeResultRepository.findAll();

        List<Long> signalIds = results.stream()
                .filter(r -> r.getSignal() != null)
                .map(r -> r.getSignal().getId())
                .toList();
        Map<Long, Double> confBySignal = signalIds.isEmpty() ? Map.of()
                : signalExplanationRepository.findBySignalIdIn(signalIds).stream()
                    .filter(e -> CALIBRATION_FACTOR.equals(e.getFactor()) && e.getScore() != null && e.getSignal() != null)
                    .collect(Collectors.toMap(e -> e.getSignal().getId(), SignalExplanation::getScore, (x, y) -> x));

        int nBuckets = EDGES.length - 1;
        int[] counts = new int[nBuckets];
        int[] wins = new int[nBuckets];
        int totalResolved = 0;
        int totalWins = 0;

        for (TradeResult r : results) {
            if (r.getSignal() == null || r.getProfitLoss() == null) continue;
            Double conf = confBySignal.get(r.getSignal().getId());
            if (conf == null) continue;
            int b = bucketIndex(conf);
            if (b < 0) continue;
            boolean win = r.getProfitLoss() > 0;
            counts[b]++;
            if (win) wins[b]++;
            totalResolved++;
            if (win) totalWins++;
        }

        List<Bucket> buckets = new ArrayList<>(nBuckets);
        for (int i = 0; i < nBuckets; i++) {
            double mid = (EDGES[i] + Math.min(EDGES[i + 1], 100.0)) / 2.0;
            double actual = counts[i] > 0 ? round4((double) wins[i] / counts[i]) : 0.0;
            double pred = calibrator.probabilityOfWin(mid); // -1 when untrained
            Double modelPred = pred >= 0 ? round4(pred) : null;
            String label = String.format("%.0f-%.0f%%", EDGES[i], Math.min(EDGES[i + 1], 100.0));
            buckets.add(new Bucket(label, mid, counts[i], wins[i], actual, modelPred));
        }

        double overall = totalResolved > 0 ? round4((double) totalWins / totalResolved) : 0.0;
        return new Report(calibrator.isTrained(), calibrator.sampleCount(), round4(calibrator.breakEvenWinRate()),
                totalResolved, overall, buckets);
    }

    private static int bucketIndex(double conf) {
        for (int i = 0; i < EDGES.length - 1; i++) {
            if (conf >= EDGES[i] && conf < EDGES[i + 1]) return i;
        }
        return -1;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
