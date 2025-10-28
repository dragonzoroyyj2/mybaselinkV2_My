package com.mybaselinkV2.app.entity;

import jakarta.persistence.*;

/**
 * 🎯 RoleEntity - 사용자 권한 엔티티
 *
 * 테이블: roles
 * 예: ROLE_USER, ROLE_ADMIN
 */
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // PK

    @Column(nullable = false, length = 50, unique = true)
    private String name; // 권한 이름

    // =====================
    // Getter / Setter
    // =====================
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
