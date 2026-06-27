package com.nifty.analysis.repository;

import com.nifty.analysis.entity.TradeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeResultRepository extends JpaRepository<TradeResult, Long> {

    Optional<TradeResult> findBySignalId(Long signalId);

    @Query("select tr from TradeResult tr where tr.signal.signalTime between :start and :end")
    List<TradeResult> findBySignalTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Sum of NET realised P&L for trades whose signal fired at/after the given time (avoids N+1). */
    @Query("select coalesce(sum(tr.profitLoss), 0) from TradeResult tr where tr.signal.signalTime >= :start")
    double sumProfitLossSince(@Param("start") LocalDateTime start);
}
