package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 🔐 UserEntity - 사용자 정보 엔티티 (로그인/프로필용)
 *
 * - 이 엔티티는 실제 프로젝트에서 회원관리용으로 사용됩니다.
 * - 필요 시 컬럼 추가/제거 가능 (현재는 기본 필드만 포함).
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password", nullable = false, length = 100)
    private String password;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "email", length = 100, unique = true)
    private String email;

    @Column(name = "role", nullable = false, length = 50)
    private String role = "ROLE_USER";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public UserEntity() {}

    public UserEntity(String username, String password, String fullName, String email, String role) {
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
    }

    // Getter / Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
