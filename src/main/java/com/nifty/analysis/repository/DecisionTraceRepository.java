package com.nifty.analysis.repository;

import com.nifty.analysis.entity.DecisionTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DecisionTraceRepository extends JpaRepository<DecisionTrace, Long> {

    /** Most recent evaluation traces (for the observability endpoint). */
    List<DecisionTrace> findTop100ByOrderByEvaluationTimeDesc();

    List<DecisionTrace> findTop100ByInstrumentOrderByEvaluationTimeDesc(String instrument);

    /**
     * Trade-generation funnel: how many evaluations ended at each reject stage (and how many emitted)
     * since {@code since}. Returns rows of [outcome, rejectStage, count].
     */
    @Query("SELECT d.outcome, d.rejectStage, COUNT(d) FROM DecisionTrace d " +
           "WHERE d.evaluationTime >= :since GROUP BY d.outcome, d.rejectStage ORDER BY COUNT(d) DESC")
    List<Object[]> funnelSince(@Param("since") LocalDateTime since);
}
