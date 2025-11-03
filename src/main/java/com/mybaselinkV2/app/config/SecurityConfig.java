package com.mybaselinkV2.app.config;

import com.mybaselinkV2.app.jwt.CustomLogoutHandler;
import com.mybaselinkV2.app.jwt.JwtAuthenticationFilter;
import com.mybaselinkV2.app.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * ✅ MyBaseLinkV2 - SecurityConfig (2025-11 최종 버전)
 * --------------------------------------------------------
 * - Spring Boot 3.5.x / Spring Security 6.x 대응
 * - JWT 기반 인증 (STATELESS)
 * - /api/stockList/**, /api/p01a05/** : 로그인 없이 접근 가능
 * - 그 외 API : JWT 인증 필요
 * --------------------------------------------------------
 */
@Configuration
@EnableAsync
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomUserDetailsService userDetailsService;
    private final CustomLogoutHandler customLogoutHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CustomUserDetailsService userDetailsService,
            CustomLogoutHandler customLogoutHandler
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
        this.customLogoutHandler = customLogoutHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            // ✅ JWT 사용이므로 CSRF 비활성화
            .csrf(csrf -> csrf.disable())

            // ✅ 접근 정책 정의
            .authorizeHttpRequests(auth -> auth

                // 정적 리소스 완전 허용
                .requestMatchers(
                        "/favicon.ico",
                        "/favicon/**",
                        "/manifest.json",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/webjars/**",
                        "/common/**"
                ).permitAll()

                // 로그인/인증 관련 엔드포인트 허용
                .requestMatchers("/", "/login", "/auth/**").permitAll()

                // 공개 API 허용 (SSE, KRX 등)
                .requestMatchers(
                        "/api/stock/batch/events",
                        "/api/stock/batch/active/**",
                        "/api/stock/batch/status/**",
                        "/api/stock/batch/sse",
                        "/api/krx/**"
                ).permitAll()

                // ✅ 새로 추가된 공개 API
                .requestMatchers("/api/stockList/**").permitAll()
                .requestMatchers("/api/p01a05List/**").permitAll()

                // ✅ 특정 API는 인증 필요
                .requestMatchers(
                        "/api/stock/batch/update/**",
                        "/api/stock/batch/cancel/**"
                ).authenticated()

                // ✅ 페이지 접근은 로그인 필요
                .requestMatchers("/pages/**").authenticated()

                // ✅ 나머지 모든 요청은 인증 필요
                .anyRequest().authenticated()
            )

            // ✅ 세션 미사용 (JWT 기반)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ✅ 사용자 인증 서비스
            .userDetailsService(userDetailsService)

            // ✅ 로그아웃 핸들러
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .addLogoutHandler(customLogoutHandler)
            )

            // ✅ JWT 필터 등록
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
