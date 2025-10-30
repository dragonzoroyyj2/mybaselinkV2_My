package com.mybaselinkV2.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mybaselinkV2.app.entity.UserEntity;

import java.util.Optional;

/**
 * ✅ UserRepository - 프로필 및 권한 조회 전용
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);
}
