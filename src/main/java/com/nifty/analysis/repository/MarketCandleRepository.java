package com.nifty.analysis.repository;

import com.nifty.analysis.entity.MarketCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketCandleRepository extends JpaRepository<MarketCandle, Long> {
    
    @Query("SELECT c FROM MarketCandle c WHERE c.timeframe = :timeframe ORDER BY c.timestamp DESC LIMIT :limit")
    List<MarketCandle> findLatestByTimeframe(@Param("timeframe") String timeframe, @Param("limit") int limit);

    @Query("SELECT c FROM MarketCandle c WHERE c.timeframe = :timeframe AND c.timestamp <= :time ORDER BY c.timestamp DESC")
    List<MarketCandle> findHistoryBefore(@Param("timeframe") String timeframe, @Param("time") LocalDateTime time, org.springframework.data.domain.Pageable pageable);

    // --- P5-2 instrument-scoped variants ---

    @Query("SELECT c FROM MarketCandle c WHERE c.instrument = :instrument AND c.timeframe = :timeframe ORDER BY c.timestamp DESC LIMIT :limit")
    List<MarketCandle> findLatestByInstrumentAndTimeframe(@Param("instrument") String instrument, @Param("timeframe") String timeframe, @Param("limit") int limit);

    @Query("SELECT c FROM MarketCandle c WHERE c.instrument = :instrument AND c.timeframe = :timeframe AND c.timestamp <= :time ORDER BY c.timestamp DESC")
    List<MarketCandle> findHistoryBeforeByInstrument(@Param("instrument") String instrument, @Param("timeframe") String timeframe, @Param("time") LocalDateTime time, org.springframework.data.domain.Pageable pageable);
}
