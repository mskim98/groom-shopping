package groom.backend.infrastructure.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// "toss" 서킷브레이커의 상태 전이(CLOSED↔OPEN↔HALF_OPEN)를 구독해 구조화 로그로 남긴다.
// Micrometer 가 resilience4j_circuitbreaker_state 지표를 자동 노출하므로, 여기서는 전이 시점만 추적한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventListener {

    // CircuitBreakerRegistry : resilience4j-spring-boot3 가 자동 등록하는 빈. 회로 인스턴스를 이름으로 꺼낸다.
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // @PostConstruct : 빈 생성 직후 1회 실행되어 상태 전이 리스너를 등록한다.
    @PostConstruct
    public void registerEventListener() {
        circuitBreakerRegistry.circuitBreaker("toss")
                .getEventPublisher()
                .onStateTransition(event -> log.warn("[CB_STATE_CHANGE] toss: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }
}
