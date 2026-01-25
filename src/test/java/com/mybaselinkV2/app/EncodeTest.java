package com.mybaselinkV2.app;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class EncodeTest {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        
        String rawPassword = "1234"; // 실제 사용할 비밀번호
        
        // 1. 암호화 진행
        String encodedPassword = encoder.encode(rawPassword);
        
        System.out.println("====================================================");
        System.out.println("원본 비밀번호: " + rawPassword);
        System.out.println("암호화된 비밀번호 (DB에 복사해서 넣으세요): ");
        System.out.println(encodedPassword);
        System.out.println("====================================================");
        
        // 2. 검증 테스트 (DB에 넣기 전 확인용)
        boolean isMatch = encoder.matches(rawPassword, encodedPassword);
        System.out.println("검증 결과 (true가 나와야 함): " + isMatch);
        System.out.println("====================================================");
    }
}