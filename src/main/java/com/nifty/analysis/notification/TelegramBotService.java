package com.nifty.analysis.notification;

import com.nifty.analysis.entity.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramBotService {

    @Value("${nifty.telegram.enabled:false}")
    private boolean enabled;

    @Value("${nifty.telegram.bot-token:}")
    private String botToken;

    @Value("${nifty.telegram.chat-id:}")
    private String chatId;

    private final WebClient.Builder webClientBuilder;

    public void sendSignal(TradeSignal signal, List<String> reasons) {
        String text = formatSignalMessage(signal, reasons);
        // Escape underscores to prevent Telegram Markdown parsing errors (e.g. BUY_CE)
        text = text.replace("_", "\\_");
        
        log.info("\n=== GENERATED SIGNAL ALERT ===\n{}\n==============================", text);
        
        if (!enabled) {
            log.info("Telegram notification skipped (nifty.telegram.enabled is false)");
            return;
        }

        if (botToken.isEmpty() || chatId.isEmpty()) {
            log.warn("Telegram bot token or chat ID is empty. Skipping notification dispatch.");
            return;
        }

        java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                .fromUriString("https://api.telegram.org/bot" + botToken + "/sendMessage")
                .queryParam("chat_id", chatId)
                .queryParam("text", text)
                .queryParam("parse_mode", "Markdown")
                .build()
                .toUri();
        
        webClientBuilder.build().post()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        response -> log.info("Telegram message sent successfully! Response: {}", response),
                        error -> log.error("Failed to send Telegram message", error)
                );
    }

    public void sendMessage(String text) {
        // Escape underscores to prevent Telegram Markdown parsing errors
        text = text.replace("_", "\\_");
        
        log.info("\n=== GENERATED TELEGRAM UPDATE ===\n{}\n==============================", text);
        
        if (!enabled) {
            log.info("Telegram notification skipped (nifty.telegram.enabled is false)");
            return;
        }

        if (botToken.isEmpty() || chatId.isEmpty()) {
            log.warn("Telegram bot token or chat ID is empty. Skipping notification dispatch.");
            return;
        }

        java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                .fromUriString("https://api.telegram.org/bot" + botToken + "/sendMessage")
                .queryParam("chat_id", chatId)
                .queryParam("text", text)
                .queryParam("parse_mode", "Markdown")
                .build()
                .toUri();
        
        webClientBuilder.build().post()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        response -> log.info("Telegram message sent successfully! Response: {}", response),
                        error -> log.error("Failed to send Telegram message", error)
                );
    }

    private String formatSignalMessage(TradeSignal signal, List<String> reasons) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚀 *BUY SIGNAL*\n\n");
        sb.append("*Strike:* ").append(signal.getStrike()).append(" ").append(signal.getSignalType().replace("BUY_", "")).append("\n\n");
        sb.append("*Entry:* ").append(signal.getEntry()).append("\n\n");
        sb.append("*SL:* ").append(signal.getStopLoss()).append("\n\n");
        sb.append("*Target 1:* ").append(signal.getTarget1()).append("\n");
        sb.append("*Target 2:* ").append(signal.getTarget2()).append("\n\n");
        sb.append("*Confidence:* ").append(Math.round(signal.getConfidence())).append("%\n\n");
        
        if (reasons != null && !reasons.isEmpty()) {
            sb.append("*Reason:*\n");
            for (String reason : reasons) {
                sb.append("✓ ").append(reason).append("\n");
            }
        }
        
        return sb.toString();
    }
}
