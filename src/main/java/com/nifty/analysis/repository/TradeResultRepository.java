package com.nifty.analysis.repository;

import com.nifty.analysis.entity.TradeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface TradeResultRepository extends JpaRepository<TradeResult, Long> {
    
    Optional<TradeResult> findBySignalId(Long signalId);
}
