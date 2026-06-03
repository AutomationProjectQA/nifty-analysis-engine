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

    @Column(name = "signal_time", nullable = false)
    private LocalDateTime signalTime;

    @Column(name = "signal_type", nullable = false, length = 10)
    private String signalType; // "BUY_CE", "BUY_PE"

    @Column(name = "strike", nullable = false)
    private Integer strike;

    @Column(name = "entry", nullable = false)
    private Double entry;

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
}
