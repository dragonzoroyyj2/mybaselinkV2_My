package com.mybaselinkV2.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder; // WAR 배포 필수 import
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer; // WAR 배포 필수 import
import org.springframework.cache.annotation.EnableCaching; // 캐싱 활성화
// import org.springframework.scheduling.annotation.EnableScheduling; // 스케줄링 비활성화

/*

| 실행 환경                                | 동작 방식                                                         |
| ------------------------------------ | ------------------------------------------------------------- |
| `java -jar MyBaseLinkV2.jar` (로컬 실행) | `main()` 메서드가 실행 → 내장 톰캣 구동                                   |
| `MyBaseLinkV2.war` (운영 톰캣 배포)        | `SpringBootServletInitializer`의 `configure()`가 호출됨 → 외부 톰캣 구동 |

-------------------------------------------------------------------------------------------------------------

| 항목                             | 설명                                             |
| ------------------------------ | ---------------------------------------------- |
| `SpringBootServletInitializer` | 외부 톰캣(WAR 배포)에서 `DispatcherServlet` 등록을 위한 진입점 |
| `configure()`                  | WAR이 외부 컨테이너에 배포될 때, context 초기화용              |
| `main()`                       | JAR 또는 IDE 실행 시 내장 톰캣 구동용                      |
| `@EnableCaching`               | Caffeine 캐시 활성화용, 성능 저하와 무관                    |
| `@EnableScheduling`            | 스케줄링을 명시적으로 켜거나 끌 수 있음 (지금은 비활성화 OK)           |

*/


@SpringBootApplication
@EnableCaching // 캐싱 기능 활성화
// @EnableScheduling // 스케줄링 기능 비활성화
// 💡 외부 Tomcat 배포를 위해 반드시 SpringBootServletInitializer를 상속해야 합니다.
public class MyBaseLinkV2Application extends SpringBootServletInitializer {

	public static void main(String[] args) {
		
		// Spring Application 실행 직전에 환경 변수 확인
	    String pythonPath = System.getenv("PYTHON_EXECUTABLE");
	    System.out.println(">> System Env Check: PYTHON_EXECUTABLE = " + pythonPath);
		
		SpringApplication.run(MyBaseLinkV2Application.class, args);
	}

    // 💡 WAR 배포를 위해 반드시 필요한 configure 메서드 오버라이드
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(MyBaseLinkV2Application.class);
    }
}