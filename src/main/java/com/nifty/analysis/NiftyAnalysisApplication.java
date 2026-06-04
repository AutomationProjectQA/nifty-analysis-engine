package com.nifty.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = RedisRepositoriesAutoConfiguration.class)
@EnableScheduling
@EnableCaching
public class NiftyAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(NiftyAnalysisApplication.class, args);
    }
}
