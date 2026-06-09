package com.nifty.analysis.repository;

import com.nifty.analysis.entity.TradeReflection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TradeReflectionRepository extends JpaRepository<TradeReflection, Long> {
    List<TradeReflection> findTop3ByOrderByFailedAtDesc();
}
