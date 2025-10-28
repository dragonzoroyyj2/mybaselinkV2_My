package com.mybaselinkV2.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching; // Cache 활성화를 위한 import
import org.springframework.scheduling.annotation.EnableScheduling; // Scheduling 활성화를 위한 import

@SpringBootApplication
@EnableCaching // 캐싱 기능 활성화
@EnableScheduling // 스케줄링 기능 활성화
public class MyBaseLinkV2Application {

	public static void main(String[] args) {
		SpringApplication.run(MyBaseLinkV2Application.class, args);
	}

}
