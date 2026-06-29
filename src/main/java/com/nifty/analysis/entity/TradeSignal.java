package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_signal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // P5-2: which instrument this signal is for (NIFTY, BANKNIFTY, ...). Defaults to NIFTY.
    @Column(name = "instrument", nullable = false)
    private String instrument = "NIFTY";

    @Column(name = "signal_time", nullable = false)
    private LocalDateTime signalTime;

    @Column(name = "signal_type", nullable = false, length = 10)
    private String signalType; // "BUY_CE", "BUY_PE"

    // P5-1: strategy type (LONG_CALL, BULL_CALL_SPREAD, IRON_CONDOR, ...). Null = legacy single-leg long.
    @Column(name = "strategy", length = 30)
    private String strategy;

    @Column(name = "strike", nullable = false)
    private Integer strike;

    @Column(name = "entry", nullable = false)
    private Double entry;

    @Column(name = "quantity")
    private Integer quantity; // executed quantity (lots * lot size); used for INR P&L

    @Column(name = "stop_loss", nullable = false)
    private Double stopLoss;

    @Column(name = "target1", nullable = false)
    private Double target1;

    @Column(name = "target2", nullable = false)
    private Double target2;

    @Column(name = "confidence", nullable = false)
    private Double confidence;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // "ACTIVE", "COMPLETED", "FAILED"

    @Column(name = "thesis", columnDefinition = "TEXT")
    private String thesis;

    // Broker order id when a real order was placed (null for paper/simulated signals).
    // Enables reconciliation against the broker order book.
    @Column(name = "order_id", length = 50)
    private String orderId;

    // Nifty spot at entry, captured at signal time (avoids reconstructing it later).
    @Column(name = "entry_spot")
    private Double entrySpot;
}
