package com.nifty.analysis.service;

import com.nifty.analysis.dto.OptionPremiumDto;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Builds per-strike CE/PE premiums for the strategy payoff builder. Premiums are
 * theoretical (Black-Scholes from the option chain's IV), which works regardless
 * of whether the live broker feed is connected. Live LTP can overlay this later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OptionPremiumService {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final OptionSnapshotRepository optionSnapshotRepository;
    private final RedisService redisService;
    private final BlackScholesService blackScholesService;

    public OptionPremiumDto.Response latestPremiums() {
        // Spot from the freshest market snapshot (Redis first, then DB).
        double spot = redisService.getLatestMarketSnapshot()
                .or(marketSnapshotRepository::findLatest)
                .map(MarketSnapshot::getNiftySpot)
                .orElse(0.0);

        // Latest option chain (Redis first, then DB).
        List<OptionSnapshot> chain = redisService.getLatestOptionChain();
        if (chain.isEmpty()) {
            java.time.LocalDateTime latest = optionSnapshotRepository.findLatestSnapshotTime();
            chain = latest != null ? optionSnapshotRepository.findBySnapshotTime(latest) : List.of();
        }

        // Fall back to a strike-derived spot if no market snapshot is available.
        if (spot <= 0 && !chain.isEmpty()) {
            spot = chain.stream().mapToInt(OptionSnapshot::getStrikePrice).average().orElse(0.0);
        }

        LocalDate expiry = com.nifty.analysis.util.TimeUtil.nextWeeklyExpiry(com.nifty.analysis.util.TimeUtil.todayIst());
        long days = com.nifty.analysis.util.TimeUtil.daysToWeeklyExpiry(com.nifty.analysis.util.TimeUtil.todayIst());
        double years = days / 365.0;

        final double spotFinal = spot;
        List<OptionPremiumDto.StrikePremium> premiums = chain.stream()
                .filter(o -> o.getStrikePrice() != null)
                .sorted(Comparator.comparingInt(OptionSnapshot::getStrikePrice))
                .map(o -> {
                    double iv = o.getIv() != null && o.getIv() > 0 ? o.getIv() : 12.5;
                    // Prefer the real broker LTP when present; fall back to the theoretical
                    // Black-Scholes price (which needs a positive spot) otherwise.
                    double ce = o.getCeLtp() != null && o.getCeLtp() > 0
                            ? o.getCeLtp()
                            : blackScholesService.price(spotFinal, o.getStrikePrice(), iv, years, true);
                    double pe = o.getPeLtp() != null && o.getPeLtp() > 0
                            ? o.getPeLtp()
                            : blackScholesService.price(spotFinal, o.getStrikePrice(), iv, years, false);
                    return new OptionPremiumDto.StrikePremium(o.getStrikePrice(), iv, ce, pe);
                })
                .toList();

        return new OptionPremiumDto.Response(
                Math.round(spotFinal * 100.0) / 100.0, expiry.toString(), days, premiums);
    }
}
