package com.nifty.analysis.repository;

import com.nifty.analysis.entity.TradeSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TradeSignalRepository extends JpaRepository<TradeSignal, Long> {
    
    List<TradeSignal> findByStatus(String status);
    
    java.util.Optional<TradeSignal> findFirstByStrikeAndSignalTypeAndStatus(int strike, String signalType, String status);
    
    List<TradeSignal> findBySignalTimeAfter(java.time.LocalDateTime time);

    List<TradeSignal> findAllByOrderBySignalTimeDesc();
}
