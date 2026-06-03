package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "confidence_weight")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConfidenceWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factor", nullable = false, unique = true, length = 50)
    private String factor; // "Trend", "OI", "PCR", "VWAP", etc.

    @Column(name = "weight", nullable = false)
    private Double weight;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
