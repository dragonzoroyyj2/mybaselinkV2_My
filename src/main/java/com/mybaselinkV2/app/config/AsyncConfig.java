package com.mybaselinkV2.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;


@Configuration
@EnableAsync
public class AsyncConfig {
	/*
	 * 
	 * AsyncConfig 클래스는 Spring 애플리케이션에서 비동기 처리 기능을 활성화하는 역할을 합니다. 
		@Configuration
		이 클래스가 Spring의 설정 파일 역할을 한다는 것을 나타냅니다.
		Spring 컨테이너가 애플리케이션을 시작할 때, 이 클래스에 정의된 빈(Bean)을 로드하고 관리하게 됩니다. 
		@EnableAsync
		핵심 기능: @Async 어노테이션이 붙은 메서드를 별도의 스레드에서 비동기적으로 실행할 수 있도록 Spring의 기능을 켜줍니다.
		작동 방식: @EnableAsync가 활성화되면 Spring은 @Async 메서드가 호출될 때, 해당 메서드를 직접 실행하는 대신 프록시 객체를 통해 호출합니다. 이 프록시가 새로운 스레드를 생성하거나 스레드 풀에서 스레드를 가져와 메서드를 실행하게 됩니다.
		메서드 분리: 비동기적으로 실행하고 싶은 메서드에 @Async를 붙여주면, 해당 메서드는 호출 즉시 반환되고 백그라운드에서 실행됩니다. 
		사용 예시 (제공된 코드 기준)
		제공된 LastCloseDownwardService.java 코드에서 다음과 같은 메서드에 @Async 어노테이션이 붙어 있습니다.
		startLastCloseDownwardTask()
		startFetchChartTask()
		AsyncConfig의 @EnableAsync 덕분에, 이 두 메서드는 메인 스레드를 차단하지 않고 백그라운드 스레드에서 실행될 수 있습니다. 이를 통해 사용자는 서버의 응답을 즉시 받고, 실제 Python 스크립트 실행과 같은 오래 걸리는 작업은 비동기로 처리됩니다. 
		요약
		AsyncConfig는 @EnableAsync 어노테이션을 통해 Spring이 @Async 메서드를 인식하고 비동기적으로 처리할 수 있는 환경을 만들어주는 중요한 설정 클래스입니다. 
	 * 
	 */
	
	/*
	 *  // 별도 설정은 필요 없음.
    		// Spring Boot는 기본적으로 SimpleAsyncTaskExecutor를 사용합니다.
    		// 필요 시 ThreadPoolTaskExecutor를 Bean으로 등록해 커스터마이징 가능.
   
 	// 비동기 작업용 스레드 풀 설정
    //ChartPatternService, TaskStatusService 등에서 사용
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 코어 스레드 수 (기본 실행 스레드)
        executor.setCorePoolSize(4);

        // 최대 스레드 수 (요청이 몰릴 때 확장)
        executor.setMaxPoolSize(8);

        // 대기열 크기 (요청이 많을 때 대기 가능한 작업 수)
        executor.setQueueCapacity(100);

        // 스레드 이름 접두사 (로그 식별용)
        executor.setThreadNamePrefix("ChartTask-");

        // 종료 시 남은 작업 처리
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
    */
	
	// 파이썬 호출 렉걸리는 현상 으로 추가
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("AsyncWorker-");
        executor.initialize();
        return executor;
    }
	
}
