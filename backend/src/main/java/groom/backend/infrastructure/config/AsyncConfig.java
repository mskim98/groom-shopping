package groom.backend.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 알림 처리를 위한 비동기 Executor
     * - 결제 완료 후 알림 처리는 별도 스레드에서 실행
     * - 알림 실패해도 결제 응답에 영향 없음
     */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);          // 기본 스레드 수
        executor.setMaxPoolSize(10);          // 최대 스레드 수
        executor.setQueueCapacity(100);       // 큐 크기
        executor.setThreadNamePrefix("notification-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 스레드풀 초기화
        executor.initialize();

        log.info("[ASYNC_CONFIG] Notification executor initialized - CorePoolSize: {}, MaxPoolSize: {}, QueueCapacity: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());

        return executor;
    }

    /**
     * 알림 생성 및 전송을 위한 동적 스레드 풀
     * - CPU 코어 수 기반 동적 조정
     * - 애플리케이션 종료 시 자동 정리
     */
    @Bean(name = "notificationProcessingExecutor", destroyMethod = "shutdown")
    public ExecutorService notificationProcessingExecutor() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, availableProcessors);  // 최소 2개
        int maxPoolSize = availableProcessors * 2;            // 최대 코어 수 * 2
        
        // 큐 크기를 5000으로 증가하여 대량 작업 처리 가능
        // Chunk 단위 처리로 메모리 사용량이 제한되므로 큐 크기 증가 가능
        int queueCapacity = 5000;
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,              // 기본 스레드 수
                maxPoolSize,               // 최대 스레드 수
                60L, TimeUnit.SECONDS,     // 유휴 스레드 유지 시간
                new LinkedBlockingQueue<>(queueCapacity),  // 작업 큐 (버퍼링)
                r -> {
                    Thread thread = new Thread(r, "notification-processing-" + System.currentTimeMillis());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()  // 큐 가득 찰 경우 호출 스레드에서 실행
        );

        log.info("[ASYNC_CONFIG] Notification processing executor initialized - CorePoolSize: {}, MaxPoolSize: {}, QueueCapacity: {}",
                corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }
}
