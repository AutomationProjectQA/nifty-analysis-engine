package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "trade_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome; // "TARGET1", "TARGET2", "STOP_LOSS", "EXPIRED"

    @Column(name = "profit_loss", nullable = false)
    private Double profitLoss;

    @Column(name = "holding_time")
    private Long holdingTime; // in seconds

    @Column(name = "accuracy")
    private Double accuracy;
}
