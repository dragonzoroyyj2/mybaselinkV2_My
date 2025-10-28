package com.mybaselinkV2.app.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * ⚡ CacheConfig - Caffeine 기반 캐시 설정
 * JWT 토큰 검증 속도 향상 및 DB 부하 감소
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES) // 캐시 10분 유지
                .maximumSize(10000); // 최대 캐시 항목 수
    }

    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        CaffeineCacheManager manager = new CaffeineCacheManager("jwtTokens");
        manager.setCaffeine(caffeine);
        return manager;
    }
}
