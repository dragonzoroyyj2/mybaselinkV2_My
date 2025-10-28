package com.mybaselinkV2.app.jwt;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import com.mybaselinkV2.app.respsitory.JwtTokenRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * 🚪 CustomLogoutHandler
 *
 * - 로그아웃 시 JWT 토큰 DB revoked 처리
 */
@Component
public class CustomLogoutHandler implements LogoutHandler {

    private final JwtTokenRepository jwtTokenRepository;

    public CustomLogoutHandler(JwtTokenRepository jwtTokenRepository) {
        this.jwtTokenRepository = jwtTokenRepository;
    }

    @Override
    public void logout(HttpServletRequest request,
                       HttpServletResponse response,
                       Authentication authentication) {

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Optional.ofNullable(jwtTokenRepository.findByToken(token))
                    .ifPresent(opt -> opt.ifPresent(t -> {
                        t.setRevoked(true);
                        jwtTokenRepository.save(t);
                    }));
        }
    }
}
