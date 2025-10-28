package com.mybaselinkV2.app.service;

import com.mybaselinkV2.app.entity.LoginUserEntity;
import com.mybaselinkV2.app.entity.UserEntity;
import com.mybaselinkV2.app.respsitory.LoginUserRepository;
import com.mybaselinkV2.app.respsitory.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 로그인/프로필 조회 서비스 (UserEntity 기반 프로필, LoginUserEntity 기반 로그인)
 */
@Service
public class LoginUserService {

    private final LoginUserRepository loginUserRepository;
    private final UserRepository userRepository;

    public LoginUserService(LoginUserRepository loginUserRepository,
                            UserRepository userRepository) {
        this.loginUserRepository = loginUserRepository;
        this.userRepository = userRepository;
    }

    public LoginUserEntity findByUsername(String username) {
        return loginUserRepository.findByUsername(username);
    }

    public Optional<Map<String, Object>> findUserProfile(String username) {
        Optional<UserEntity> userOpt = userRepository.findByUsername(username);
        if (userOpt.isPresent()) {
            UserEntity u = userOpt.get();
            return Optional.of(Map.of(
                    "username", u.getUsername(),
                    "name", u.getFullName() != null ? u.getFullName() : u.getUsername(),
                    "email", u.getEmail() != null ? u.getEmail() : ""
            ));
        }
        LoginUserEntity l = loginUserRepository.findByUsername(username);
        if (l != null) {
            return Optional.of(Map.of(
                    "username", l.getUsername(),
                    "name", l.getUsername(),
                    "email", ""
            ));
        }
        return Optional.empty();
    }
}
