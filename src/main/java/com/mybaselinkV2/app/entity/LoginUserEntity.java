package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;

/**
 * 🔐 LoginUserEntity - 로그인 전용 사용자 엔티티
 *
 * - 테이블명: users (요청하신 대로 유지)
 * - 이 엔티티는 "로그인 전용" 으로 간단한 필드만 보유합니다.
 * - 추후 회원관리(UserEntity 등)를 별도 엔티티로 만들 계획이면 그때 추가 컬럼/테이블을 사용하세요.
 */
@Entity
@Table(name = "users")
public class LoginUserEntity {

    /** PK: 자동 증가 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 아이디 (unique) - 사용자가 입력하는 username */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** 암호화된 비밀번호 (BCrypt 등으로 암호화되어 저장) */
    @Column(nullable = false)
    private String password;

    /** 역할 (예: ROLE_USER, ROLE_ADMIN) - 앞에 ROLE_ 붙여서 저장 권장 */
    @Column(nullable = false, length = 50)
    private String role;

    // 기본 생성자
    public LoginUserEntity() {}

    // 간단 생성자 (테스트용)
    public LoginUserEntity(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    // Getter / Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
