package com.nifty.analysis.service;

import com.nifty.analysis.dto.OptionPremiumDto;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.repository.MarketSnapshotRepository;
import com.nifty.analysis.repository.OptionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

        LocalDate expiry = nextWeeklyExpiry(LocalDate.now());
        long days = Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), expiry));
        double years = days / 365.0;

        final double spotFinal = spot;
        List<OptionPremiumDto.StrikePremium> premiums = chain.stream()
                .filter(o -> o.getStrikePrice() != null)
                .sorted(Comparator.comparingInt(OptionSnapshot::getStrikePrice))
                .map(o -> {
                    double iv = o.getIv() != null && o.getIv() > 0 ? o.getIv() : 12.5;
                    double ce = blackScholesService.price(spotFinal, o.getStrikePrice(), iv, years, true);
                    double pe = blackScholesService.price(spotFinal, o.getStrikePrice(), iv, years, false);
                    return new OptionPremiumDto.StrikePremium(o.getStrikePrice(), iv, ce, pe);
                })
                .toList();

        return new OptionPremiumDto.Response(
                Math.round(spotFinal * 100.0) / 100.0, expiry.toString(), days, premiums);
    }

    /** Next Thursday (Nifty weekly expiry); rolls to next week if today is past Thursday. */
    private LocalDate nextWeeklyExpiry(LocalDate today) {
        LocalDate d = today;
        while (d.getDayOfWeek() != DayOfWeek.THURSDAY) {
            d = d.plusDays(1);
        }
        return d;
    }
}
