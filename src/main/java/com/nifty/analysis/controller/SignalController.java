package com.nifty.analysis.controller;

import com.nifty.analysis.entity.TradeSignal;
import com.nifty.analysis.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/signals")
@RequiredArgsConstructor
@Slf4j
public class SignalController {

    private final TradeSignalRepository tradeSignalRepository;

    @GetMapping
    public ResponseEntity<List<TradeSignal>> getAllSignals() {
        log.info("REST request to fetch all options buy signals");
        List<TradeSignal> signals = tradeSignalRepository.findAllByOrderBySignalTimeDesc();
        return ResponseEntity.ok(signals);
    }

    @GetMapping("/active")
    public ResponseEntity<List<TradeSignal>> getActiveSignals() {
        log.info("REST request to fetch active options buy signals");
        List<TradeSignal> activeSignals = tradeSignalRepository.findByStatus("ACTIVE");
        return ResponseEntity.ok(activeSignals);
    }
}
