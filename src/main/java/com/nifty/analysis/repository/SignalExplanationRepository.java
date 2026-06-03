package com.nifty.analysis.repository;

import com.nifty.analysis.entity.SignalExplanation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SignalExplanationRepository extends JpaRepository<SignalExplanation, Long> {
    
    List<SignalExplanation> findBySignalId(Long signalId);

    List<SignalExplanation> findBySignalIdIn(List<Long> signalIds);
}
