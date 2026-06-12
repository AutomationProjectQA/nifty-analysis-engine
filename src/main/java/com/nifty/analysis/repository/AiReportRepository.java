package com.nifty.analysis.repository;

import com.nifty.analysis.entity.AiReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AiReportRepository extends JpaRepository<AiReport, Long> {

    @Query("SELECT r FROM AiReport r WHERE r.type = :type ORDER BY r.publishDate DESC, r.generatedAt DESC LIMIT 1")
    Optional<AiReport> findLatestByType(@Param("type") String type);

    Optional<AiReport> findByTypeAndPublishDate(String type, LocalDate publishDate);

    Page<AiReport> findByTypeOrderByPublishDateDesc(String type, Pageable pageable);
}
