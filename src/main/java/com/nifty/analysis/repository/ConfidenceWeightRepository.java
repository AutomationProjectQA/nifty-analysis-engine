package com.nifty.analysis.repository;

import com.nifty.analysis.entity.ConfidenceWeight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ConfidenceWeightRepository extends JpaRepository<ConfidenceWeight, Long> {
    
    List<ConfidenceWeight> findByActiveTrue();
}
