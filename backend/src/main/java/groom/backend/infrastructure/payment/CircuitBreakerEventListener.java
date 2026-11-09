package groom.backend.infrastructure.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 레지스트리의 모든 서킷브레이커(toss · toss-cancel · 이후 생기는 인스턴스)의 상태 전이(CLOSED↔OPEN↔HALF_OPEN)를 구독해 구조화 로그로 남긴다.
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
        // 이름 하나만 붙이면 나머지 인스턴스(toss-cancel)의 전이가 로그에 남지 않는다 - 지금 있는 것 전부에 붙인다
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::logTransitions);
        // 인스턴스는 첫 호출에 lazily 만들어지므로, 등록 시점 이후에 추가되는 것에도 붙인다
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> logTransitions(event.getAddedEntry()));
    }

    private void logTransitions(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("[CB_STATE_CHANGE] {}: {} -> {}",
                        circuitBreaker.getName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }
}
