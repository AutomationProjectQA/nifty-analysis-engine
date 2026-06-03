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
}
