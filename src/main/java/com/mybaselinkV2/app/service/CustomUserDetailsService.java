package com.mybaselinkV2.app.service;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.mybaselinkV2.app.entity.LoginUserEntity;


/**
 * 🔐 CustomUserDetailsService - Spring Security용 UserDetailsService 구현
 *
 * 역할:
 * 1. Spring Security 인증 시 사용자의 UserDetails 로드
 * 2. LoginUserEntity → UserDetails 변환
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final LoginUserService userService;

    public CustomUserDetailsService(LoginUserService userService) {
        this.userService = userService;
    }

    /**
     * 🔹 username으로 사용자 조회 후 UserDetails 반환
     *
     * @param username 로그인 ID
     * @return UserDetails
     * @throws UsernameNotFoundException
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LoginUserEntity user = userService.findByUsername(username);

        if (user == null) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username);
        }

        // 권한 설정 (ROLE 접두사 포함)
        GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole());

        // Spring Security UserDetails 객체 생성
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword()) // DB에 저장된 암호화된 비밀번호 사용
                .authorities(authority)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}
