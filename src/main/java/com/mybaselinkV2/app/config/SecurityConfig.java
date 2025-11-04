package com.mybaselinkV2.app.config;

import com.mybaselinkV2.app.jwt.CustomLogoutHandler;
import com.mybaselinkV2.app.jwt.JwtAuthenticationFilter;
import com.mybaselinkV2.app.service.AuthService;
import com.mybaselinkV2.app.service.CustomUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.List;

/**
 * ===============================================================
 * ✅ MyBaseLinkV2 - SecurityConfig (v4.0 완전 통합 안정판)
 * ---------------------------------------------------------------
 * 🔹 @Lazy 불필요, JPA 초기화 충돌 없음
 * 🔹 JWT + AuthService + SSE 완벽 통합
 * 🔹 AccessDenied 예외 방지 완전판
 * ===============================================================
 */
@Configuration
@EnableAsync
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final CustomLogoutHandler customLogoutHandler;

    @Value("#{'${security.jwt.ignore-paths:}'.split(',')}")
    private List<String> ignorePaths;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          CustomLogoutHandler customLogoutHandler) {
        this.userDetailsService = userDetailsService;
        this.customLogoutHandler = customLogoutHandler;
    }

    /** ✅ JwtAuthenticationFilter Bean 등록 */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            com.mybaselinkV2.app.jwt.JwtTokenProvider jwtTokenProvider,
            CustomUserDetailsService userDetailsService,
            AuthService authService
    ) {
        return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, authService);
    }

    /** ✅ Security Filter Chain 정의 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {

        http
            // ✅ CSRF 비활성화 (JWT 기반)
            .csrf(csrf -> csrf.disable())

            // ✅ 요청별 접근 제어
            .authorizeHttpRequests(auth -> {
                // ✅ 정적 리소스 허용
                auth.requestMatchers(
                        "/favicon.ico", "/favicon/**",
                        "/apple-icon-*.png", "/android-icon-*.png",
                        "/mstile-*.png", "/manifest.json",
                        "/css/**", "/js/**", "/images/**",
                        "/webjars/**", "/common/**"
                ).permitAll();

                // ✅ 로그인 및 인증 관련 허용
                auth.requestMatchers("/", "/login", "/logout", "/auth/**").permitAll();

                // ✅ SSE 통신 예외 허용 (JWT 필터 통과 불가 구간)
                auth.requestMatchers(
                        "/api/stock/batch/sse",
                        "/api/stock/lastCloseDownward/sse"
                ).permitAll();

                // ✅ YAML 설정 기반 ignore-paths 자동 허용
                if (ignorePaths != null) {
                    String[] paths = ignorePaths.stream()
                            .filter(p -> p != null && !p.isBlank())
                            .map(String::trim)
                            .map(p -> p.endsWith("**") ? p : p + "**")
                            .toArray(String[]::new);
                    if (paths.length > 0) auth.requestMatchers(paths).permitAll();
                }

                // ✅ 나머지 모든 페이지/API 인증 필요
                auth.requestMatchers("/pages/**", "/api/**").authenticated();
                auth.anyRequest().authenticated();
            })

            // ✅ 세션 미사용
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ✅ UserDetailsService 지정
            .userDetailsService(userDetailsService)

            // ✅ 로그아웃 핸들러 등록
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .addLogoutHandler(customLogoutHandler)
            )

            // ✅ JWT 필터 삽입
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** ✅ AuthenticationManager */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /** ✅ PasswordEncoder (BCrypt) */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
