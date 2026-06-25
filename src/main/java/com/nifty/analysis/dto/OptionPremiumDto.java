package com.nifty.analysis.dto;

import java.util.List;

/** Per-strike theoretical CE/PE premiums for the strategy payoff builder. */
public class OptionPremiumDto {

    public record StrikePremium(int strike, double iv, double cePremium, double pePremium) {}

    public record Response(double spot, String expiry, long daysToExpiry, List<StrikePremium> premiums) {}

    private OptionPremiumDto() {}
}
