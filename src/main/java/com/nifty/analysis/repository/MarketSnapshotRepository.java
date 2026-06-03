package com.nifty.analysis.repository;

import com.nifty.analysis.entity.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, Long> {
    
    @Query("SELECT m FROM MarketSnapshot m ORDER BY m.snapshotTime DESC LIMIT 1")
    Optional<MarketSnapshot> findLatest();

    @Query("SELECT m FROM MarketSnapshot m WHERE m.snapshotTime <= :time ORDER BY m.snapshotTime DESC LIMIT 1")
    Optional<MarketSnapshot> findLatestBefore(@Param("time") LocalDateTime time);

    @Query("SELECT m FROM MarketSnapshot m WHERE m.snapshotTime < :time ORDER BY m.snapshotTime DESC")
    List<MarketSnapshot> findHistoryBefore(@Param("time") LocalDateTime time, Pageable pageable);

    @Query("SELECT m FROM MarketSnapshot m WHERE m.snapshotTime >= :start AND m.snapshotTime <= :end")
    List<MarketSnapshot> findBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
