package com.nifty.analysis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * Observability record of ONE signal-evaluation pass: which gate (if any) rejected the candidate,
 * the per-gate notes, and the final outcome. This is the Phase-0 "decision trace" that makes the
 * trade-generation funnel measurable — query by {@code reject_stage} to see where candidates die.
 */
@Entity
@Table(name = "decision_trace")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DecisionTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Correlates all rows of one evaluation pass (and, later, one collection cycle). */
    @Column(name = "cycle_id", length = 64)
    private String cycleId;

    @Column(name = "instrument", nullable = false, length = 20)
    private String instrument;

    @Column(name = "evaluation_time", nullable = false)
    private LocalDateTime evaluationTime;

    /** BULLISH / BEARISH / null (rejected before a direction was chosen). */
    @Column(name = "direction", length = 10)
    private String direction;

    @Column(name = "final_confidence")
    private Double finalConfidence;

    @Column(name = "effective_gate")
    private Double effectiveGate;

    /** EMITTED | REJECTED. */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    /** The gate that rejected the candidate (null when EMITTED). Group by this for the funnel. */
    @Column(name = "reject_stage", length = 40)
    private String rejectStage;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** Human-readable, newline-separated per-gate notes accumulated during the pass. */
    @Column(name = "gate_detail", columnDefinition = "TEXT")
    private String gateDetail;

    @Column(name = "signals_emitted")
    private Integer signalsEmitted = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
