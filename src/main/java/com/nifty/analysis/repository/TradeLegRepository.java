package com.nifty.analysis.repository;

import com.nifty.analysis.entity.TradeLeg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeLegRepository extends JpaRepository<TradeLeg, Long> {

    List<TradeLeg> findBySignalId(Long signalId);
}
