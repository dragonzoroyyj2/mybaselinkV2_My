package com.mybaselinkV2.app.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mybaselinkV2.app.entity.JwtTokenEntity;
import com.mybaselinkV2.app.service.JwtService;

/**
 * 🔒 JwtController
 *
 * JWT 토큰 관련 REST API
 */
@RestController
@RequestMapping("/api/jwt")
public class JwtController {

    private final JwtService jwtService;

    public JwtController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 특정 토큰 조회
     */
    @GetMapping("/{token}")
    public ResponseEntity<JwtTokenEntity> getToken(@PathVariable String token) {
        return jwtService.getToken(token)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 사용자 활성 토큰 조회
     */
    @GetMapping("/active/{username}")
    public ResponseEntity<List<JwtTokenEntity>> getActiveTokens(@PathVariable String username) {
        return ResponseEntity.ok(jwtService.getActiveTokens(username));
    }

    /**
     * 만료 토큰 삭제
     */
    @DeleteMapping("/expired")
    public ResponseEntity<Long> deleteExpiredTokens() {
        long deleted = jwtService.deleteExpiredTokens();
        return ResponseEntity.ok(deleted);
    }

    /**
     * 토큰 강제 무효화
     */
    @PostMapping("/revoke")
    public ResponseEntity<Void> revokeToken(@RequestParam String token) {
        jwtService.revokeToken(token);
        return ResponseEntity.ok().build();
    }
}
