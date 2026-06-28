package com.nifty.analysis.service;

import com.nifty.analysis.dto.OptionSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class OptionsIndicatorService {

    /**
     * Calculates the overall Put-Call Ratio (PCR) of the entire option chain.
     * Formula: Total Put OI / Total Call OI
     */
    public double calculateOverallPcr(List<OptionSnapshotDto> optionChain) {
        long totalCallOi = 0;
        long totalPutOi = 0;

        for (OptionSnapshotDto strike : optionChain) {
            totalCallOi += strike.ceOi() != null ? strike.ceOi() : 0L;
            totalPutOi += strike.peOi() != null ? strike.peOi() : 0L;
        }

        if (totalCallOi == 0) {
            return 1.0; // no data → NEUTRAL (1.0), not 0.0 (which downstream reads as strongly bearish)
        }

        double pcr = (double) totalPutOi / totalCallOi;
        return Math.round(pcr * 100.0) / 100.0;
    }

    /**
     * Calculates the overall Volume-weighted Put-Call Ratio (Volume PCR) of the entire option chain.
     * Formula: Total Put Volume / Total Call Volume
     */
    public double calculateVolumePcr(List<OptionSnapshotDto> optionChain) {
        long totalCallVol = 0;
        long totalPutVol = 0;

        for (OptionSnapshotDto strike : optionChain) {
            totalCallVol += strike.ceVolume() != null ? strike.ceVolume() : 0L;
            totalPutVol += strike.peVolume() != null ? strike.peVolume() : 0L;
        }

        if (totalCallVol == 0) {
            return 1.0; // no data → NEUTRAL (1.0), not 0.0 (which downstream reads as strongly bearish)
        }

        double volPcr = (double) totalPutVol / totalCallVol;
        return Math.round(volPcr * 100.0) / 100.0;
    }

    /**
     * Calculates the Max Pain strike price.
     * The strike at which option sellers (writers) experience the minimum total loss.
     */
    public double calculateMaxPain(List<OptionSnapshotDto> optionChain) {
        if (optionChain == null || optionChain.isEmpty()) {
            return 0.0;
        }

        double minLoss = Double.MAX_VALUE;
        double maxPainStrike = 0.0;

        // Evaluate loss at each strike in the chain if the spot settled there
        for (OptionSnapshotDto candidateStrike : optionChain) {
            double candidatePrice = candidateStrike.strikePrice();
            double totalLoss = 0.0;

            for (OptionSnapshotDto strikeData : optionChain) {
                double strike = strikeData.strikePrice();
                long ceOi = strikeData.ceOi() != null ? strikeData.ceOi() : 0L;
                long peOi = strikeData.peOi() != null ? strikeData.peOi() : 0L;

                // Call writer loss if settlement price > call strike
                if (candidatePrice > strike) {
                    totalLoss += (candidatePrice - strike) * ceOi;
                }
                // Put writer loss if settlement price < put strike
                if (candidatePrice < strike) {
                    totalLoss += (strike - candidatePrice) * peOi;
                }
            }

            if (totalLoss < minLoss) {
                minLoss = totalLoss;
                maxPainStrike = candidatePrice;
            }
        }

        return maxPainStrike;
    }

    /**
     * Detects the Open Interest (OI) Build-up Type for a given option contract.
     * 
     * For Calls (CE): Option Price movement is positively correlated with Nifty Spot movement.
     * For Puts (PE): Option Price movement is negatively correlated with Nifty Spot movement.
     *
     * Classification:
     * - Long Build-up: Option Price rises (+), OI rises (+)
     * - Short Covering: Option Price rises (+), OI falls (-)
     * - Short Build-up: Option Price falls (-), OI rises (+)
     * - Long Unwinding: Option Price falls (-), OI falls (-)
     */
    public BuildUpType detectBuildUp(boolean isCall, double spotChange, long oiChange) {
        boolean optionPriceUp;
        if (isCall) {
            optionPriceUp = spotChange > 0;
        } else {
            optionPriceUp = spotChange < 0;
        }

        if (optionPriceUp) {
            return oiChange >= 0 ? BuildUpType.LONG_BUILD_UP : BuildUpType.SHORT_COVERING;
        } else {
            return oiChange >= 0 ? BuildUpType.SHORT_BUILD_UP : BuildUpType.LONG_UNWINDING;
        }
    }

    public enum BuildUpType {
        LONG_BUILD_UP("Long Build-up"),
        SHORT_COVERING("Short Covering"),
        SHORT_BUILD_UP("Short Build-up"),
        LONG_UNWINDING("Long Unwinding");

        private final String label;

        BuildUpType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
