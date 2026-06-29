package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "market_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MarketSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // P5-2: which instrument this row belongs to (NIFTY, BANKNIFTY, ...). Defaults to NIFTY.
    @Column(name = "instrument", nullable = false)
    private String instrument = "NIFTY";

    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    @Column(name = "nifty_spot", nullable = false)
    private Double niftySpot;

    @Column(name = "nifty_future", nullable = false)
    private Double niftyFuture;

    @Column(name = "india_vix", nullable = false)
    private Double indiaVix;

    @Column(name = "volume")
    private Double volume;

    @Column(name = "ema20")
    private Double ema20;

    @Column(name = "ema50")
    private Double ema50;

    @Column(name = "rsi")
    private Double rsi;

    @Column(name = "vwap")
    private Double vwap;

    // Intraday range, previous close, and 52-week extremes (from the live broker quote).
    @Column(name = "day_high")
    private Double dayHigh;

    @Column(name = "day_low")
    private Double dayLow;

    @Column(name = "prev_close")
    private Double prevClose;

    @Column(name = "week52_high")
    private Double week52High;

    @Column(name = "week52_low")
    private Double week52Low;
}
