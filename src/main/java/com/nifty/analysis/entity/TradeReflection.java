package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_reflection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradeReflection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(name = "failed_at", nullable = false)
    private LocalDateTime failedAt;

    @Column(name = "reflection_text", nullable = false, columnDefinition = "TEXT")
    private String reflectionText;
}
