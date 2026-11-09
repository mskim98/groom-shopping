package groom.backend.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig {

    // 가상 스레드를 켜면 Boot 가 SimpleAsyncTaskScheduler 를 만들어 spring.task.scheduling.pool.size 를 무시한다.
    // 그 상태에서는 fixed-delay 작업(Outbox 폴러 1초 · 보상 배치 · Raffle · SSE · 재고 정합)이 스레드 1개를 나눠 써서
    // 보상 회차(최대 ~100초) 동안 다른 작업이 멈추고 정합 주기가 60초에서 161초로 늘어난다(c_r5a1 실측).
    // 이 빈이 있으면 Boot 의 자동 구성은 물러나고 @Scheduled 는 이 풀을 쓴다
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // 작업이 던진 예외로 스레드가 죽는 일은 없지만, 어느 작업이 실패했는지 로그에 남긴다
        scheduler.setErrorHandler(t -> log.error("[SCHEDULED_TASK_ERROR] {}", t.getMessage(), t));
        return scheduler;
    }
}
