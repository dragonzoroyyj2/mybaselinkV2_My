package com.mybaselinkV2.app.scheduler;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mybaselinkV2.app.repository.JwtTokenRepository;

/**
 * ⏰ JwtTokenCleanupScheduler
 *
 * - 만료된 JWT 토큰을 매일 자동 삭제
 * - @Transactional 적용으로 커넥션 누수 방지
 */
@Component
public class JwtTokenCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenCleanupScheduler.class);
    private final JwtTokenRepository tokenRepository;

    public JwtTokenCleanupScheduler(JwtTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /**
     * 🔹 매일 새벽 3시 실행
     *
     * - DB에서 만료 토큰을 한 번에 삭제
     * - deletedCount 로그 기록
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void removeExpiredTokens() {
        Instant now = Instant.now();
        long deletedCount = tokenRepository.deleteAllByExpiresAtBefore(now);
        logger.info("⏰ JwtTokenCleanupScheduler - 만료 토큰 삭제 완료. 삭제된 토큰 수: {}", deletedCount);
    }
}
