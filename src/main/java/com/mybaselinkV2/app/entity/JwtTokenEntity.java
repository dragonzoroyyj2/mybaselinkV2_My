package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 🔐 JwtTokenEntity (안정형 완전판)
 *
 * ✅ JWT 토큰 관리용 엔티티
 * - 토큰 발급, 만료, 무효화 상태 추적
 * - 중복 로그인 방지 / 세션 연장 시 갱신
 */
@Entity
@Table(name = "jwt_tokens")
public class JwtTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    // ✅ 기본 생성자
    public JwtTokenEntity() {}

    // ✅ 전체 필드 생성자
    public JwtTokenEntity(Long id, String token, String username, boolean revoked, Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.token = token;
        this.username = username;
        this.revoked = revoked;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    // ✅ getter/setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
