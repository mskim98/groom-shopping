package groom.backend.application.payment;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 낙관적 락 차감 전략으로 띄운 컨텍스트에서 부모 클래스의 시험을 그대로 다시 돌린다.
 * 이 전략이 시도마다 REQUIRES_NEW 로 독립 커밋하면 후처리 롤백 뒤에도 재고가 차감된 채 남는다(R9 재고 누수)
 */
@SpringBootTest(properties = {"stock.decrease-strategy=optimistic", "spring.kafka.streams.auto-startup=false"})
@DisplayName("결제 후처리 롤백 시 재고 원복 - 낙관적 락 전략(T3-1)")
class OptimisticPaymentPostProcessRollbackTest extends PaymentPostProcessRollbackTest {
}
