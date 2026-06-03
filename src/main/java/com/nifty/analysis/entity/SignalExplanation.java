package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "signal_explanation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignalExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(name = "factor", nullable = false, length = 50)
    private String factor; // "Trend", "OI", "PCR", "VWAP", etc.

    @Column(name = "score", nullable = false)
    private Double score;

    @Column(name = "comment", length = 255)
    private String comment;
}
