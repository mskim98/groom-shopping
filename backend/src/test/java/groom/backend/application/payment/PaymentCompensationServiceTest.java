package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.infrastructure.payment.TossAlreadyCanceledException;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.TossRetryLaterException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationServiceTest {

    @Mock
    private PaymentCompensationRepository compensationRepository;
    @Mock
    private TossPaymentClient tossPaymentClient;
    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Mock
    private ObjectProvider<PaymentCompensationService> selfProvider;
    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Spy
    private PaymentCompensationBatchProperties batchProperties = new PaymentCompensationBatchProperties();
    @Spy
    private io.micrometer.core.instrument.MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @InjectMocks
    private PaymentCompensationService service;

    @BeforeEach
    void resetSkipCircuits() {
        // 대부분의 테스트는 스킵 판정을 거치지 않으므로 정지 신호 회로를 비워 회로 조회를 막는다
        // 스킵 테스트만 이 목록을 채운다
        batchProperties.setSkipWhenOpen(List.of());
    }

    private PaymentCompensation newCompensation(int maxRetry) {
        return PaymentCompensation.builder()
                .paymentId(UUID.randomUUID())
                .paymentKey("pk-1")
                .amount(1000)
                .reason("DB 후처리 실패")
                .maxRetryCount(maxRetry)
                .build();
    }

    private PaymentCompensationService proxy() {
        PaymentCompensationService proxy = mock(PaymentCompensationService.class);
        given(selfProvider.getObject()).willReturn(proxy);
        return proxy;
    }

    // 취소 직전 결제 상태 조회를 지정 상태로 고정한다. FAILED 면 취소를 진행한다
    private void stubPaymentStatus(PaymentStatus status) {
        Payment payment = mock(Payment.class);
        given(payment.getStatus()).willReturn(status);
        given(paymentRepository.findById(any())).willReturn(Optional.of(payment));
    }

    // ── 실행 단계: PG 호출은 트랜잭션 밖, 결과 기록만 프록시(REQUIRES_NEW) ──

    @Test
    @DisplayName("PG 취소 호출을 먼저 하고 그 결과를 값으로 프록시의 recordResult 에 넘긴다")
    void executeCompensation_callsPgThenRecordsResultViaProxy() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation compensation = newCompensation(5);
        stubPaymentStatus(PaymentStatus.FAILED);

        service.executeCompensation(compensation);

        InOrder inOrder = inOrder(tossPaymentClient, proxy);
        inOrder.verify(tossPaymentClient).cancelPaymentUncounted(eq("pk-1"), anyString(), anyString());
        inOrder.verify(proxy).recordResult(compensation, null);
        // 실행 단계 자체는 장부를 건드리지 않는다 - 기록은 recordResult 만 한다
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("PG 호출 실패는 예외를 값으로 넘겨 기록하고 호출부로 전파하지 않는다")
    void executeCompensation_passesFailureAsValue() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation compensation = newCompensation(5);
        stubPaymentStatus(PaymentStatus.FAILED);
        RuntimeException failure = new RuntimeException("toss down");
        given(tossPaymentClient.cancelPaymentUncounted(anyString(), anyString(), anyString())).willThrow(failure);

        service.executeCompensation(compensation);

        verify(proxy).recordResult(compensation, failure);
    }

    @Test
    @DisplayName("결제가 DONE 이면 취소를 호출하지 않고 SUPERSEDED 로 기록한다")
    void executeCompensation_결제가_DONE이면_취소를_호출하지_않고_SUPERSEDED로_기록한다() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation compensation = newCompensation(5);
        stubPaymentStatus(PaymentStatus.DONE);

        service.executeCompensation(compensation);

        verify(tossPaymentClient, never()).cancelPaymentUncounted(anyString(), anyString(), anyString());
        verify(proxy).recordSuperseded(eq(compensation), anyString());
    }

    @Test
    @DisplayName("결과 기록만 REQUIRES_NEW 트랜잭션이고 PG 호출 단계에는 트랜잭션이 없다")
    void onlyRecordResultIsTransactional() throws NoSuchMethodException {
        Method execute = PaymentCompensationService.class
                .getMethod("executeCompensation", PaymentCompensation.class);
        Method record = PaymentCompensationService.class
                .getMethod("recordResult", PaymentCompensation.class, Exception.class);

        // PG 응답을 기다리는 동안 DB 커넥션을 쥐면 실패 1건이 타임아웃(30초)만큼 풀을 점유한다
        assertThat(execute.getAnnotation(Transactional.class)).isNull();
        Transactional tx = record.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ── 기록 단계 ──

    @Test
    void recordResult_재시도_한도_초과로_GIVEN_UP되면_DLQ_아웃박스를_적재한다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        // maxRetry=3 → 3번째 실패에서 GIVEN_UP 전이
        for (int i = 0; i < 3; i++) {
            service.recordResult(compensation, new RuntimeException("toss down"));
        }

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.GIVEN_UP);
        ArgumentCaptor<PaymentOutbox> captor = ArgumentCaptor.forClass(PaymentOutbox.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTopic()).isEqualTo("payment-compensation-dlq");
        assertThat(captor.getValue().getEventType()).isEqualTo("COMPENSATION_GIVEN_UP");
    }

    @Test
    void recordResult_성공이면_SUCCEEDED_이고_DLQ로_발행하지_않는다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        service.recordResult(compensation, null);

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("429 계열(TossRetryLaterException)은 retryCount 를 올리지 않고 다음 시각만 미룬다")
    void recordResult_재시도대기_예외면_retryCount_유지한다() {
        // @InjectMocks 는 @Value 필드를 채우지 않으므로 운영 기본값(45초)을 직접 넣는다
        ReflectionTestUtils.setField(service, "retryLaterDeferMs", 45000L);
        PaymentCompensation compensation = newCompensation(3);
        compensation.markProcessing();
        given(compensationRepository.save(any())).willReturn(compensation);

        LocalDateTime before = LocalDateTime.now();
        service.recordResult(compensation, new TossRetryLaterException("429 too many requests"));
        LocalDateTime after = LocalDateTime.now();

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(compensation.getRetryCount()).isEqualTo(0);
        // 45초 ±20% 지터 - 36~54초 뒤
        assertThat(compensation.getNextRetryAt()).isBetween(before.plusSeconds(35), after.plusSeconds(55));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("TossAlreadyCanceledException 은 SUCCEEDED 로 기록하고 DLQ 로 발행하지 않는다")
    void recordResult_이미취소_예외면_SUCCEEDED로_기록한다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        service.recordResult(compensation, new TossAlreadyCanceledException("이미 취소된 결제"));

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
        verify(outboxRepository, never()).save(any());
    }

    // ── 진입점 ──

    @Test
    @DisplayName("결정 C — compensate 는 대기 기록만 하고 PG 취소는 하지 않는다(배치가 유일 취소 경로)")
    void compensateOnlyRecordsIntentAndDoesNotCancel() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation recorded = newCompensation(5);
        given(proxy.recordCompensationIntent(any(), anyString(), any(), anyString())).willReturn(recorded);

        service.compensate(UUID.randomUUID(), "pk-1", 1000, "DB 후처리 실패");

        // 요청 스레드가 취소 응답을 기다리면 커넥션이 취소 지연만큼 물린다 - 취소는 배치가 한다
        verify(proxy).recordCompensationIntent(any(), anyString(), any(), anyString());
        verify(tossPaymentClient, never()).cancelPaymentUncounted(anyString(), anyString(), anyString());
        verify(proxy, never()).executeCompensation(any());
        verify(proxy, never()).recordResult(any(), any());
    }

    @Test
    @DisplayName("배치 재시도도 PG 호출 뒤 결과 기록을 프록시로 넘긴다")
    void batchRetryRecordsResultViaProxy() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation pending = newCompensation(5);
        given(proxy.acquireRetryableBatch()).willReturn(List.of(pending));
        stubPaymentStatus(PaymentStatus.FAILED);

        service.retryPendingCompensations();

        verify(tossPaymentClient).cancelPaymentUncounted(eq("pk-1"), anyString(), anyString());
        verify(proxy).recordResult(pending, null);
    }

    @Test
    @DisplayName("결정 C — 정지 신호 회로가 OPEN 이면 회차를 통째로 쉬고 선점 조회조차 하지 않는다")
    void skipsRoundWhenSkipCircuitOpen() {
        batchProperties.setSkipWhenOpen(List.of("toss-cancel"));
        CircuitBreaker open = mock(CircuitBreaker.class);
        given(open.getState()).willReturn(CircuitBreaker.State.OPEN);
        given(circuitBreakerRegistry.circuitBreaker("toss-cancel")).willReturn(open);

        service.retryPendingCompensations();

        // 죽은 PG 로 한 페이지를 쏟지 않도록, 선점 조회(프록시 경유)·취소를 아예 하지 않는다
        verify(selfProvider, never()).getObject();
        verify(tossPaymentClient, never()).cancelPaymentUncounted(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("선점 조회 건수는 batch-size 설정을 따른다(상수 아님)")
    void acquireUsesBatchSizeProperty() {
        batchProperties.setBatchSize(37);
        given(compensationRepository.findRetryable(any(), any(), anyInt())).willReturn(List.of());

        service.acquireRetryableBatch();

        verify(compensationRepository).findRetryable(any(), any(), eq(37));
    }

    // ── Task 4: 적재 판정(carried > 0 → 강제 OPEN) ──

    private PaymentCompensationService setUpBatchRound(long due, java.util.List<PaymentCompensation> page) {
        PaymentCompensationService proxy = proxy();
        given(proxy.acquireRetryableBatch()).willReturn(page);
        given(compensationRepository.countRetryable(any(), any())).willReturn(due);
        return proxy;
    }

    @Test
    @DisplayName("한 페이지로 대기를 못 비우면(carried > 0) open-on-carried 회로를 강제 OPEN 한다")
    void forcesOpenWhenCarriedPositive() {
        // due 300, 이번 회차가 가져간 페이지 100 → carried 200
        setUpBatchRound(300, java.util.List.of(newCompensation(5), newCompensation(5)));
        CircuitBreaker flag = mock(CircuitBreaker.class);
        given(circuitBreakerRegistry.circuitBreaker("toss-cancel")).willReturn(flag);

        service.retryPendingCompensations();

        verify(flag).transitionToOpenState();
    }

    @Test
    @DisplayName("한 페이지로 대기를 다 비우면(carried == 0) 강제 OPEN 하지 않는다")
    void doesNotForceOpenWhenCarriedZero() {
        // due 2 = 페이지 2 → carried 0
        setUpBatchRound(2, java.util.List.of(newCompensation(5), newCompensation(5)));

        service.retryPendingCompensations();

        // open-on-carried 회로를 아예 조회하지 않는다
        verify(circuitBreakerRegistry, never()).circuitBreaker("toss-cancel");
    }

    @Test
    @DisplayName("보조 조건(onlyWhenNoSuccess)이 켜지면 그 페이지에 성공이 있으면 carried > 0 이어도 강제 OPEN 하지 않는다")
    void skipsForceOpenWhenSuccessAndOnlyWhenNoSuccess() {
        batchProperties.setOpenOnCarriedOnlyWhenNoSuccess(true);
        PaymentCompensationService proxy = setUpBatchRound(300, java.util.List.of(newCompensation(5), newCompensation(5)));
        // recordResult 가 성공(true)을 돌려주면 succeeded > 0 이 되어 강제 OPEN 을 건너뛴다
        given(proxy.recordResult(any(), any())).willReturn(true);

        service.retryPendingCompensations();

        verify(circuitBreakerRegistry, never()).circuitBreaker("toss-cancel");
    }

    // ── T5: 선점 조회는 배치와 독립된 REQUIRES_NEW 트랜잭션이다 ──

    @Test
    @DisplayName("배치는 선점 조회를 프록시로 호출하고, 선점 조회 자체는 REQUIRES_NEW 트랜잭션이다")
    void retryPendingCompensations_delegatesAcquireToProxy() {
        PaymentCompensationService proxy = proxy();
        given(proxy.acquireRetryableBatch()).willReturn(List.of());

        service.retryPendingCompensations();

        verify(proxy).acquireRetryableBatch();
        verify(compensationRepository, never()).findRetryable(any(), any(), anyInt());
    }

    @Test
    @DisplayName("acquireRetryableBatch 는 REQUIRES_NEW 트랜잭션이다")
    void acquireRetryableBatchIsTransactional() throws NoSuchMethodException {
        Method acquire = PaymentCompensationService.class.getMethod("acquireRetryableBatch");

        Transactional tx = acquire.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
