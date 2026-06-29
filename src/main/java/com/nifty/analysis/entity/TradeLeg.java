package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * P5-1: one leg of a multi-leg defined-risk strategy (spread / iron condor). Many legs belong to
 * one {@link TradeSignal}. A single-leg long option does NOT use this — it keeps using the
 * TradeSignal's own strike/entry/SL/target fields.
 */
@Entity
@Table(name = "trade_leg")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradeLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(name = "action", nullable = false, length = 4)
    private String action; // "BUY" / "SELL"

    @Column(name = "option_type", nullable = false, length = 2)
    private String optionType; // "CE" / "PE"

    @Column(name = "strike", nullable = false)
    private Integer strike;

    @Column(name = "entry_premium", nullable = false)
    private Double entryPremium;

    @Column(name = "exit_premium")
    private Double exitPremium;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** Signed direction for P&L: +1 long (BUY), -1 short (SELL). */
    public int sign() {
        return "SELL".equalsIgnoreCase(action) ? -1 : 1;
    }
}
