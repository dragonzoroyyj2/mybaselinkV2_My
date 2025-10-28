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
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth

                // ✅ 정적 리소스 완전 허용
                .requestMatchers(
                        "/favicon.ico",
                        "/favicon/**",
                        "/manifest.json",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/common/**"
                ).permitAll()

                .requestMatchers("/", "/login").permitAll()
                .requestMatchers("/auth/**").permitAll()

                // ✅ 페이지는 인증 필요
                .requestMatchers("/pages/**").authenticated()

                // ✅ 임시: 주식 API 공개
                .requestMatchers("/api/krx/**").permitAll()

                .anyRequest().authenticated()
            )
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .userDetailsService(userDetailsService)

            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .addLogoutHandler(customLogoutHandler)
            )

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
