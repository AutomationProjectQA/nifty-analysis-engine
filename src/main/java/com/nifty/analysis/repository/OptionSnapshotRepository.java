package com.nifty.analysis.repository;

import com.nifty.analysis.entity.OptionSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OptionSnapshotRepository extends JpaRepository<OptionSnapshot, Long> {
    
    @Query("SELECT MAX(o.snapshotTime) FROM OptionSnapshot o")
    LocalDateTime findLatestSnapshotTime();
    
    List<OptionSnapshot> findBySnapshotTime(LocalDateTime snapshotTime);

    List<OptionSnapshot> findByStrikePriceAndSnapshotTimeAfterOrderBySnapshotTimeDesc(Integer strikePrice, LocalDateTime time);
}
