package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import com.nifty.analysis.service.OptionsIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OptionsAgent {

    private final OptionsIndicatorService optionsIndicatorService;
    private final OptionSnapshotRepository optionSnapshotRepository;

    public AgentResponse analyze(List<OptionSnapshotDto> optionChain, double spotPrice, double spotChange) {
        List<String> comments = new ArrayList<>();
        double score = 50.0;

        if (optionChain == null || optionChain.isEmpty()) {
            return new AgentResponse(50.0, "NEUTRAL", List.of("No option chain data available"));
        }

        // 1. Calculate overall PCR
        double overallPcr = optionsIndicatorService.calculateOverallPcr(optionChain);
        if (overallPcr >= 1.2) {
            score += 15.0;
            comments.add("Overall PCR is bullish (" + overallPcr + "), indicating strong Put writing support");
        } else if (overallPcr <= 0.7) {
            score -= 15.0;
            comments.add("Overall PCR is bearish (" + overallPcr + "), indicating strong Call writing resistance");
        } else {
            comments.add("Overall PCR is neutral (" + overallPcr + ")");
        }

        // 2. Evaluate Max Pain
        double maxPain = optionsIndicatorService.calculateMaxPain(optionChain);
        if (spotPrice > maxPain) {
            score += 10.0;
            comments.add("Index trading above Max Pain strike (" + maxPain + "), putting pressure on Call writers");
        } else if (spotPrice < maxPain) {
            score -= 10.0;
            comments.add("Index trading below Max Pain strike (" + maxPain + "), putting pressure on Put writers");
        }

        // 3. Evaluate strike-wise build-up near ATM
        int atmStrike = ((int) Math.round(spotPrice / 50.0)) * 50;
        int longBuildUpCount = 0;
        int shortCoveringCount = 0;
        int shortBuildUpCount = 0;
        
        for (OptionSnapshotDto strike : optionChain) {
            // Focus on ATM +/- 100 points
            if (Math.abs(strike.strikePrice() - atmStrike) <= 100) {
                long ceOiChange = strike.ceOiChange() != null ? strike.ceOiChange() : 0L;
                long peOiChange = strike.peOiChange() != null ? strike.peOiChange() : 0L;

                OptionsIndicatorService.BuildUpType ceType = optionsIndicatorService.detectBuildUp(true, spotChange, ceOiChange);
                OptionsIndicatorService.BuildUpType peType = optionsIndicatorService.detectBuildUp(false, spotChange, peOiChange);

                if (ceType == OptionsIndicatorService.BuildUpType.LONG_BUILD_UP) shortBuildUpCount++; // Call buying is bullish, but heavy Call writing is bearish
                if (ceType == OptionsIndicatorService.BuildUpType.SHORT_COVERING) shortCoveringCount++; // Call shorts covered (bullish)
                if (peType == OptionsIndicatorService.BuildUpType.LONG_BUILD_UP) longBuildUpCount++; // Put writing / Put long build-up
            }
        }

        if (shortCoveringCount > 0 || longBuildUpCount > shortBuildUpCount) {
            score += 15.0;
            comments.add("Active bullish OI build-up near ATM strikes (Put writing and Call short-covering)");
        } else if (shortBuildUpCount > longBuildUpCount) {
            score -= 15.0;
            comments.add("Active bearish OI build-up near ATM strikes (Call writing dominance)");
        }

        // 4. Calculate Volume-weighted PCR
        double volPcr = optionsIndicatorService.calculateVolumePcr(optionChain);
        if (volPcr >= 1.2) {
            score += 10.0;
            comments.add("Volume PCR is bullish (" + volPcr + "), indicating higher volume on Puts");
        } else if (volPcr <= 0.7) {
            score -= 10.0;
            comments.add("Volume PCR is bearish (" + volPcr + "), indicating higher volume on Calls");
        } else {
            comments.add("Volume PCR is neutral (" + volPcr + ")");
        }

        // 5. Calculate ATM OI Velocity
        LocalDateTime latestTime = optionChain.stream()
                .map(OptionSnapshotDto::timestamp)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(LocalDateTime.now());

        List<OptionSnapshot> historicalAtms = optionSnapshotRepository
                .findByStrikePriceAndSnapshotTimeAfterOrderBySnapshotTimeDesc(atmStrike, latestTime.minusMinutes(5));

        if (historicalAtms != null && historicalAtms.size() >= 2) {
            OptionSnapshot newestAtm = historicalAtms.get(0);
            OptionSnapshot oldestAtm = historicalAtms.get(historicalAtms.size() - 1);
            
            long ceNow = newestAtm.getCeOi() != null ? newestAtm.getCeOi() : 0L;
            long peNow = newestAtm.getPeOi() != null ? newestAtm.getPeOi() : 0L;
            long ceOiChange5m = ceNow - (oldestAtm.getCeOi() != null ? oldestAtm.getCeOi() : 0L);
            long peOiChange5m = peNow - (oldestAtm.getPeOi() != null ? oldestAtm.getPeOi() : 0L);

            // RELATIVE threshold: a "rapid" build-up is >8% of the strike's own OI in 5m, with a
            // small absolute floor to ignore noise. (A flat 100k was huge for a thin strike and
            // tiny for ATM.)
            double ceThresh = Math.max(25000.0, 0.08 * ceNow);
            double peThresh = Math.max(25000.0, 0.08 * peNow);

            if (ceOiChange5m > ceThresh) {
                score -= 15.0;
                comments.add("Bearish ATM OI Velocity: CE OI +" + ceOiChange5m + " in 5m (> " + Math.round(ceThresh) + ") at " + atmStrike);
            } else if (peOiChange5m > peThresh) {
                score += 15.0;
                comments.add("Bullish ATM OI Velocity: PE OI +" + peOiChange5m + " in 5m (> " + Math.round(peThresh) + ") at " + atmStrike);
            } else {
                comments.add("ATM OI Velocity stable (CE " + ceOiChange5m + ", PE " + peOiChange5m + " over 5m)");
            }
        } else {
            comments.add("Insufficient historical ATM option snapshots to compute OI Velocity");
        }

        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : (score <= 40.0 ? "BEARISH" : "NEUTRAL");

        return new AgentResponse(score, bias, comments);
    }
}
