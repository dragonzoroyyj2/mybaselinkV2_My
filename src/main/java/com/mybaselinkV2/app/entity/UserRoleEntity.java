package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;

/**
 * 🎯 UserRoleEntity - 사용자와 권한 매핑 테이블
 *
 * 테이블: user_roles
 * users.id <-> roles.id 연결
 */
@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // PK

    @Column(name = "user_id", nullable = false)
    private Long userId; // users.id

    @Column(name = "role_id", nullable = false)
    private Long roleId; // roles.id

    // =====================
    // Getter / Setter
    // =====================
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
}
