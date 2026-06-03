package com.nifty.analysis.agent;

import com.nifty.analysis.dto.AgentResponse;
import com.nifty.analysis.dto.OptionSnapshotDto;
import com.nifty.analysis.service.OptionsIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OptionsAgent {

    private final OptionsIndicatorService optionsIndicatorService;

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

        score = Math.max(0.0, Math.min(100.0, score));
        String bias = score >= 60.0 ? "BULLISH" : (score <= 40.0 ? "BEARISH" : "NEUTRAL");

        return new AgentResponse(score, bias, comments);
    }
}
