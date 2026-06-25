package com.nifty.analysis.service;

import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import com.nifty.analysis.entity.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Broadcasts live market data to subscribed portal clients over STOMP/WebSocket.
 * Best-effort: a failure to publish never disrupts the collection cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketStreamPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publishMarket(MarketSnapshot snapshot) {
        send("/topic/market", snapshot);
    }

    public void publishOptions(List<OptionSnapshot> optionChain) {
        send("/topic/options", optionChain);
    }

    public void publishSignals(List<TradeSignal> signals) {
        send("/topic/signals", signals);
    }

    /** Real-time index tick (spot/future/vix) — pushed between full collection cycles. */
    public void publishTick(double niftySpot, double niftyFuture, double indiaVix) {
        send("/topic/tick", java.util.Map.of(
                "niftySpot", niftySpot,
                "niftyFuture", niftyFuture,
                "indiaVix", indiaVix,
                "ts", System.currentTimeMillis()));
    }

    /** Real-time per-strike option OI/LTP — merged onto the option chain by the portal. */
    public void publishOptionTicks(Object optionRows) {
        send("/topic/optionsTick", optionRows);
    }

    private void send(String topic, Object payload) {
        try {
            messagingTemplate.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.warn("Failed to publish to {}: {}", topic, e.getMessage());
        }
    }
}
