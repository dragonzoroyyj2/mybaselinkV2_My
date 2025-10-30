package com.mybaselinkV2.app.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mybaselinkV2.app.entity.JwtTokenEntity;
import com.mybaselinkV2.app.repository.JwtTokenRepository;

/**
 * 🔑 JwtService
 *
 * - 토큰 발급, 검증, 만료, 삭제 관리
 * - 기존 기능 100% 보존
 */
@Service
public class JwtService {

    private final JwtTokenRepository jwtTokenRepository;

    public JwtService(JwtTokenRepository jwtTokenRepository) {
        this.jwtTokenRepository = jwtTokenRepository;
    }

    /**
     * 토큰 저장
     */
    public JwtTokenEntity saveToken(JwtTokenEntity token) {
        return jwtTokenRepository.save(token);
    }

    /**
     * 토큰 조회
     */
    public Optional<JwtTokenEntity> getToken(String token) {
        return jwtTokenRepository.findByToken(token);
    }

    /**
     * 활성 토큰 조회
     */
    public List<JwtTokenEntity> getActiveTokens(String username) {
        return jwtTokenRepository.findByUsernameAndRevokedFalse(username);
    }

    /**
     * 만료된 토큰 삭제
     * ✅ 기존 deleteAllByExpiresAtBefore 기능 그대로
     */
    @Transactional
    public long deleteExpiredTokens() {
        return jwtTokenRepository.deleteAllByExpiresAtBefore(Instant.now());
    }

    /**
     * 토큰 강제 무효화
     */
    @Transactional
    public void revokeToken(String token) {
        jwtTokenRepository.findByToken(token).ifPresent(t -> {
            t.setRevoked(true);
            jwtTokenRepository.save(t);
        });
    }
}
