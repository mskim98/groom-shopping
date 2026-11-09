package groom.backend.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("서킷 상태 전이 로그 리스너(T3-11)")
class CircuitBreakerEventListenerTest {

    private final Logger listenerLogger = (Logger) LoggerFactory.getLogger(CircuitBreakerEventListener.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        listenerLogger.detachAppender(appender);
    }

    private List<String> logged() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("리스너 등록 시점에 존재하는 모든 서킷 인스턴스의 상태 전이를 로그한다")
    void 등록시점의_모든_인스턴스_전이를_로그한다() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("toss");
        registry.circuitBreaker("toss-cancel");
        new CircuitBreakerEventListener(registry).registerEventListener();

        registry.circuitBreaker("toss").transitionToOpenState();
        registry.circuitBreaker("toss-cancel").transitionToOpenState();

        assertThat(logged())
                .contains("[CB_STATE_CHANGE] toss: CLOSED -> OPEN", "[CB_STATE_CHANGE] toss-cancel: CLOSED -> OPEN");
    }

    @Test
    @DisplayName("리스너 등록 뒤 생성된 서킷 인스턴스의 상태 전이도 로그한다")
    void 등록뒤_생성된_인스턴스_전이도_로그한다() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        new CircuitBreakerEventListener(registry).registerEventListener();

        // resilience4j 는 인스턴스를 첫 호출에 lazily 만든다 - 등록 시점에 없던 이름도 잡혀야 한다
        registry.circuitBreaker("created-later").transitionToOpenState();

        assertThat(logged()).contains("[CB_STATE_CHANGE] created-later: CLOSED -> OPEN");
    }
}
