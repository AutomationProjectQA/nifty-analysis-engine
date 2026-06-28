package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "option_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OptionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // P5-2: which instrument this row belongs to (NIFTY, BANKNIFTY, ...). Defaults to NIFTY.
    @Column(name = "instrument", nullable = false)
    private String instrument = "NIFTY";

    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    @Column(name = "strike_price", nullable = false)
    private Integer strikePrice;

    @Column(name = "ce_oi")
    private Long ceOi;

    @Column(name = "pe_oi")
    private Long peOi;

    @Column(name = "ce_oi_change")
    private Long ceOiChange;

    @Column(name = "pe_oi_change")
    private Long peOiChange;

    @Column(name = "iv")
    private Double iv;

    @Column(name = "pcr")
    private Double pcr;

    @Column(name = "max_pain")
    private Double maxPain;

    @Column(name = "ce_volume")
    private Long ceVolume;

    @Column(name = "pe_volume")
    private Long peVolume;
}
