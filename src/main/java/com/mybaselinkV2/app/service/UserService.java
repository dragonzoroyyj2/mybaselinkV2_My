package com.mybaselinkV2.app.service;

import com.mybaselinkV2.app.entity.UserEntity;
import com.mybaselinkV2.app.repository.UserRepository;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** ✅ 프로필 조회 */
    public Optional<UserEntity> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /** ✅ 이메일 중복 체크용 */
    public boolean existsByEmail(String email) {
        return userRepository.findByEmail(email).isPresent();
    }

    /** ✅ 프로필 저장 */
    public UserEntity save(UserEntity user) {
        return userRepository.save(user);
    }
}
