package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentCompensationRepository;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50",
        // 배경 보상 배치(fixedDelay)가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"})
@DisplayName("동시 중복 확정 방어(T3-3)")
class PaymentConcurrencyGuardTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient; // 승인 응답 주입 · 취소 호출 횟수 확인
    @MockBean
    private ProductStockRedisRepository productStockRedisRepository; // 선점 성공 · 해제 호출 횟수 확인
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 알림 부수효과 차단

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private PaymentCompensationService compensationService;
    @Autowired
    private SpringDataPaymentCompensationRepository compensationJpa;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        given(productStockRedisRepository.preReserve(any(), anyInt())).willReturn(1L);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("같은 주문에 대한 동시 확정은 이벤트를 1건만 남기고, 같은 이벤트를 동시에 받아도 하나만 DONE 이 되며 재고는 한 번만 차감된다")
    void 동시승인_같은주문_하나만_DONE이다() throws Exception {
        String paymentKey = "pk-" + UUID.randomUUID();
        TossPaymentResponse response = PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE);
        // 두 요청이 모두 멱등 선조회를 통과한 뒤 같이 후처리로 들어가게 Toss 호출에서 서로를 기다린다
        CyclicBarrier bothAtToss = new CyclicBarrier(2);
        given(tossPaymentClient.confirmPayment(any(), anyString())).willAnswer(invocation -> {
            bothAtToss.await(10, TimeUnit.SECONDS);
            return response;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Payment>> results = List.of(
                    executor.submit(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000)),
                    executor.submit(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000)));
            for (Future<Payment> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).getStatus().name())
                        .as("컨슈머가 멈춘 상태라 두 요청 모두 오류가 아니라 READY 결제를 돌려받는다").isEqualTo("READY");
            }

            // 이벤트는 결제당 1건 - 진 쪽은 Outbox 를 더 적재하지 않는다
            List<String> payloads = jdbc.queryForList(
                    "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
            assertThat(payloads).as("outbox 행").hasSize(1);

            // 같은 이벤트를 컨슈머 두 개가 동시에 받는 재전달 경합 - 하나만 후처리를 끝내고 다른 쪽은 멱등 종료한다
            PaymentApprovedEvent event = objectMapper.readValue(payloads.get(0), PaymentApprovedEvent.class);
            CyclicBarrier bothAtConsumer = new CyclicBarrier(2);
            List<Future<?>> consumers = List.of(
                    executor.submit(() -> {
                        bothAtConsumer.await(10, TimeUnit.SECONDS);
                        paymentApplicationService.applyApprovedEvent(event);
                        return null;
                    }),
                    executor.submit(() -> {
                        bothAtConsumer.await(10, TimeUnit.SECONDS);
                        paymentApplicationService.applyApprovedEvent(event);
                        return null;
                    }));
            for (Future<?> consumer : consumers) {
                consumer.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(fixture.stockOf(productId)).as("재고 차감은 한 번뿐").isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE id = ? AND status = 'DONE'",
                Long.class, payment.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_compensation WHERE payment_id = ?",
                Long.class, payment.getId())).as("정상 결제를 취소할 보상이 생기면 안 된다").isZero();
        // 진 쪽이 선점한 Redis 재고는 돌려준다(이긴 쪽은 차감분을 유지한다)
        verify(productStockRedisRepository, times(1)).release(any(), anyInt());
    }

    private PaymentCompensation seedProcessingCompensation() {
        PaymentCompensation compensation = compensationJpa.save(PaymentCompensation.builder()
                .paymentId(payment.getId()).paymentKey(payment.getPaymentKeyValue()).amount(1000)
                .reason("DB 후처리 실패").maxRetryCount(5).build());
        jdbc.update("UPDATE payment_compensation SET status = 'PROCESSING' WHERE id = ?", compensation.getId());
        return compensationJpa.findById(compensation.getId()).orElseThrow();
    }

    private void approvePayment() {
        String key = "pk-" + UUID.randomUUID();
        payment.ready(key);
        payment.approve(key, "tx-" + key);
        payment = paymentRepository.save(payment);
    }

    @Test
    @DisplayName("보상 실행 시점에 결제가 이미 DONE 이면 SUPERSEDED 로 끝나고 Toss 취소를 호출하지 않는다")
    void 보상실행_결제가_이미_DONE이면_SUPERSEDED로_끝나고_취소호출이_없다() {
        approvePayment();
        PaymentCompensation compensation = seedProcessingCompensation();

        boolean succeeded = compensationService.executeCompensation(compensation);

        assertThat(succeeded).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_compensation WHERE id = ?",
                String.class, compensation.getId())).isEqualTo("SUPERSEDED");
        verify(tossPaymentClient, never()).cancelPaymentUncounted(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("보상 실행 시점에 결제가 FAILED 이면 Toss 취소를 호출한다")
    void 보상실행_결제가_FAILED이면_Toss취소를_호출한다() {
        paymentApplicationService.markPaymentFailed(payment.getId(), "DB_POST_PROCESS_FAILED", "후처리 실패");
        payment = paymentRepository.save(paymentRepository.findById(payment.getId()).orElseThrow());
        // 승인된 결제의 키가 있어야 취소 호출이 성립한다
        jdbc.update("UPDATE payment SET payment_key = ? WHERE id = ?", "pk-" + UUID.randomUUID(), payment.getId());
        payment = paymentRepository.findById(payment.getId()).orElseThrow();
        PaymentCompensation compensation = seedProcessingCompensation();

        boolean succeeded = compensationService.executeCompensation(compensation);

        assertThat(succeeded).isTrue();
        verify(tossPaymentClient, atLeast(1)).cancelPaymentUncounted(anyString(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_compensation WHERE id = ?",
                String.class, compensation.getId())).isEqualTo("SUCCEEDED");
    }
}
