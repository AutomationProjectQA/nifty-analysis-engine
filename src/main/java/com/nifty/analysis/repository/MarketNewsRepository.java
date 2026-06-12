package com.nifty.analysis.repository;

import com.nifty.analysis.entity.MarketNews;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketNewsRepository extends JpaRepository<MarketNews, Long> {

    List<MarketNews> findTop5ByOrderByPublishedAtDesc();

    Page<MarketNews> findAllByOrderByPublishedAtDesc(Pageable pageable);
}
