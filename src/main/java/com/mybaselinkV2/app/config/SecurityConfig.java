package com.mybaselinkV2.app.config;

import com.mybaselinkV2.app.jwt.CustomLogoutHandler;
import com.mybaselinkV2.app.jwt.JwtAuthenticationFilter;
import com.mybaselinkV2.app.service.AuthService;
import com.mybaselinkV2.app.service.CustomUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.io.PrintWriter;
import java.util.List;

/**
 * ===============================================================
 * ✅ MyBaseLinkV2 - SecurityConfig (v4.3 완전 통합 안정판)
 * ---------------------------------------------------------------
 * 🔹 JWT + AuthService + SSE 완벽 통합
 * 🔹 AccessDenied / 403 완전 해결 ( /error 경로 permitAll 추가)
 * 🔹 /api/global/status → permitAll (전역 상태 조회 전용)
 * 🔹 나머지 /api/**, /pages/** → 로그인 필요
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

    /** ✅ PasswordEncoder (BCrypt) */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** ✅ AuthenticationManager */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
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

            // ⚡️⚡️⚡️ 핵심 수정: Exception Handling 정의 ⚡️⚡️⚡️
            // API 환경에서는 리다이렉트가 아닌 JSON 응답을 즉시 반환하여 'response is already committed' 오류 방지
            .exceptionHandling(eh -> eh
                // 401 Unauthorized (인증되지 않은 사용자 접근)
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setStatus(401);
                    PrintWriter writer = response.getWriter();
                    writer.println("{\"error\": \"Unauthorized\", \"message\": \"JWT token is missing or invalid.\"}");
                })
                // 403 Forbidden (권한이 없는 사용자 접근)
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setStatus(403);
                    PrintWriter writer = response.getWriter();
                    writer.println("{\"error\": \"Forbidden\", \"message\": \"You do not have required access rights.\"}");
                })
            )

            
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
                
                // ✅ 전역 상태 조회 (403 방지용 - 로그인 없이 접근 가능)
                auth.requestMatchers("/api/global/status").permitAll();
                
                // 🚀 핵심 수정: Spring 기본 에러 처리 URL 허용 ( permitAll() 엔드포인트에서 발생하는 403 에러 방지)
                auth.requestMatchers("/error").permitAll();
                
                
                // ✅ SSE -JWT 기반
                /*
                 * SSE 는 특성상: permitAll()
					헤더 제한이 많고 CORS / Cookie 정책이 까다롭고
					Spring Security 6.x 의 AuthorizationFilter 에 매우 민감함
					인증처리 필터(JWT 필터)보다 앞단에서 AccessDenied 가 발생할 수 있음
                 */
                auth.requestMatchers(
                        "/api/stock/batch/sse",
                        "/api/stock/batch/prod/sse",
                        "/api/stock/batch/gprod/sse",
                        "/api/stock/batch/athena/sse",
                        "/api/stock/lastCloseDownward/sse"
                ).permitAll();

                // ✅ 그 외 모든 API와 페이지는 인증 필수
                auth.requestMatchers("/api/**", "/pages/**").authenticated();
                auth.anyRequest().authenticated();
            })

            // ✅ 세션 미사용 (JWT만 사용)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ✅ UserDetailsService 지정
            .userDetailsService(userDetailsService)

            // ✅ 로그아웃 핸들러 등록
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .addLogoutHandler(customLogoutHandler)
                    // ✅ 추가: 로그아웃 성공 시 200 OK 응답 처리 (API 명세에 적합)
                    .logoutSuccessHandler((request, response, authentication) -> response.setStatus(200))
            )

            // ✅ JWT 필터 삽입
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}