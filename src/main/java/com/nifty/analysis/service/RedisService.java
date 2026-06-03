package com.nifty.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nifty.analysis.entity.MarketSnapshot;
import com.nifty.analysis.entity.OptionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_MARKET_LATEST = "market:latest";
    private static final String KEY_OPTION_LATEST = "option:latest";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    public void saveLatestMarketSnapshot(MarketSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(KEY_MARKET_LATEST, json, CACHE_TTL);
            log.debug("Successfully cached latest market snapshot in Redis");
        } catch (Exception e) {
            log.error("Failed to cache market snapshot in Redis", e);
        }
    }

    public void saveLatestOptionChain(List<OptionSnapshot> optionChain) {
        try {
            String json = objectMapper.writeValueAsString(optionChain);
            redisTemplate.opsForValue().set(KEY_OPTION_LATEST, json, CACHE_TTL);
            log.debug("Successfully cached latest option chain ({} strikes) in Redis", optionChain.size());
        } catch (Exception e) {
            log.error("Failed to cache option chain in Redis", e);
        }
    }

    public Optional<MarketSnapshot> getLatestMarketSnapshot() {
        try {
            String json = redisTemplate.opsForValue().get(KEY_MARKET_LATEST);
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, MarketSnapshot.class));
            }
        } catch (Exception e) {
            log.error("Failed to read market snapshot from Redis", e);
        }
        return Optional.empty();
    }

    public List<OptionSnapshot> getLatestOptionChain() {
        try {
            String json = redisTemplate.opsForValue().get(KEY_OPTION_LATEST);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<List<OptionSnapshot>>() {});
            }
        } catch (Exception e) {
            log.error("Failed to read option chain from Redis", e);
        }
        return Collections.emptyList();
    }
}
