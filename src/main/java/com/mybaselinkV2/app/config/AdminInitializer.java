package com.mybaselinkV2.app.config; // config 패키지를 생성해 주세요.

import com.mybaselinkV2.app.repository.UserRepository; // 고객님의 실제 UserRepository 경로로 수정해야 합니다.
import com.mybaselinkV2.app.entity.UserEntity;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Configuration
public class AdminInitializer {

    // 💡 UserRepository와 PasswordEncoder를 주입받아 사용합니다.

    @Bean
    @Transactional
    public CommandLineRunner initAdminUser(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            // 사용자 엔티티를 찾을 경로와 이름은 고객님의 프로젝트에 맞게 수정해 주세요.
            // 예를 들어, 'com.mybaselinkV2.app.repository.UserRepository' 경로를 사용했다고 가정합니다.

            // 1. 이미 'admin' 계정이 존재하는지 확인
            if (userRepository.findByUsername("admin").isEmpty()) {
                
                // 2. 새로운 관리자 객체 생성
            	UserEntity adminUser = new UserEntity();
                adminUser.setUsername("admin");
                
                // 3. 비밀번호 '1234'를 BCrypt로 암호화하여 저장
                // 웹 로그인 비밀번호는 '1234'이며, DB에는 암호화된 값 저장
                adminUser.setPassword(passwordEncoder.encode("1234")); 
                adminUser.setRole("ROLE_ADMIN"); // 관리자 권한 부여
                
                // 4. DB에 저장
                userRepository.save(adminUser);

                System.out.println("✅ Initial Admin user 'admin' created successfully with password '1234'.");
            }
        };
    }
}