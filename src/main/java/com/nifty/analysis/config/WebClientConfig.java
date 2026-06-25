package com.nifty.analysis.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Central HTTP timeouts for every outbound WebClient call (Angel One, Gemini, Yahoo) so a hung
 * external API can never block the calling thread (e.g. the scheduler) indefinitely. On timeout
 * the call fails and each caller's existing fallback (template / simulated data) takes over.
 */
@Configuration
public class WebClientConfig {

    @Value("${nifty.http.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${nifty.http.timeout-ms:10000}")
    private int timeoutMs;

    @Bean
    public WebClient.Builder webClientBuilder() {
        int seconds = Math.max(1, timeoutMs / 1000);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(seconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(seconds, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
