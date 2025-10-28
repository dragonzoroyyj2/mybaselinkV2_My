package com.mybaselinkV2.app.controller;

import com.mybaselinkV2.app.jwt.JwtTokenProvider;
import com.mybaselinkV2.app.service.AuthService;
import com.mybaselinkV2.app.service.CustomUserDetailsService;
import com.mybaselinkV2.app.service.LoginUserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 인증/세션 컨트롤러 (HttpOnly 쿠키 기반)
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String COOKIE_NAME = "jwt";

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthService authService;
    private final CustomUserDetailsService userDetailsService;
    private final LoginUserService loginUserService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenProvider jwtTokenProvider,
                          AuthService authService,
                          CustomUserDetailsService userDetailsService,
                          LoginUserService loginUserService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authService = authService;
        this.userDetailsService = userDetailsService;
        this.loginUserService = loginUserService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> req,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse response) {
        String username = req.getOrDefault("username", "").trim();
        String password = req.getOrDefault("password", "");

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();

            String token = jwtTokenProvider.generateAccessToken(
                    userDetails.getUsername(),
                    userDetails.getAuthorities()
            );

            Instant now = Instant.now();
            Instant expiresAt = now.plusMillis(jwtTokenProvider.accessExpirationMillis);

            authService.login(userDetails, token, expiresAt);

            boolean secure = httpRequest.isSecure();
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                    .httpOnly(true)
                    .secure(secure)
                    .sameSite(secure ? "Strict" : "Lax")
                    .path("/")
                    .maxAge(Duration.ofMillis(jwtTokenProvider.accessExpirationMillis))
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            Map<String, Object> result = new HashMap<>();
            result.put("username", username);
            result.put("message", "로그인 성공");
            result.put("sessionMillis", jwtTokenProvider.accessExpirationMillis);
            result.put("serverTime", now.toEpochMilli());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "아이디 또는 비밀번호가 올바르지 않습니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request,
                                                       HttpServletResponse response) {
        String oldToken = extractCookieToken(request);
        if (oldToken == null || !authService.isTokenValid(oldToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "세션 만료 또는 유효하지 않음"));
        }

        long remaining = jwtTokenProvider.getRemainingMillis(oldToken);
        if (remaining > 5 * 60 * 1000L) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "세션이 충분히 남아있음"));
        }

        String username = jwtTokenProvider.getUsername(oldToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        String newToken = jwtTokenProvider.generateAccessToken(username, userDetails.getAuthorities());
        Instant expiresAt = Instant.now().plusMillis(jwtTokenProvider.accessExpirationMillis);
        authService.refreshToken(oldToken, newToken, expiresAt);

        boolean secure = request.isSecure();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, newToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "Strict" : "Lax")
                .path("/")
                .maxAge(Duration.ofMillis(jwtTokenProvider.accessExpirationMillis))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(Map.of(
                "message", "토큰 갱신 성공",
                "sessionMillis", jwtTokenProvider.accessExpirationMillis,
                "serverTime", System.currentTimeMillis()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request,
                                                      HttpServletResponse response) {
        String token = extractCookieToken(request);
        if (token != null) {
            authService.logout(token);
        }
        ResponseCookie deleteCookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, deleteCookie.toString());
        return ResponseEntity.ok(Map.of("message", "로그아웃 완료"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String token = extractCookieToken(request);
        if (token == null || !jwtTokenProvider.validateToken(token) || !authService.isTokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "인증되지 않음"));
        }
        String username = jwtTokenProvider.getUsername(token);
        Map<String, Object> profile = loginUserService.findUserProfile(username)
                .orElseGet(() -> Map.of(
                        "username", username,
                        "fullName", username,
                        "email", ""
                ));
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(HttpServletRequest request) {
        String token = extractCookieToken(request);
        if (token == null || !jwtTokenProvider.validateToken(token) || !authService.isTokenValid(token)) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
        long remainingMillis = jwtTokenProvider.getRemainingMillis(token);
        String username = jwtTokenProvider.getUsername(token);
        Map<String, Object> profile = loginUserService.findUserProfile(username)
                .orElseGet(() -> Map.of(
                        "username", username,
                        "name", username,
                        "email", ""
                ));
        Map<String, Object> body = new HashMap<>();
        body.put("valid", true);
        body.put("remainingMillis", remainingMillis);
        body.putAll(profile);
        return ResponseEntity.ok(body);
    }

    private String extractCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
