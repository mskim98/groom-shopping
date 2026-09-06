package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.kafka.PaymentCompensationDlqProducer;
import groom.backend.infrastructure.payment.TossAlreadyCanceledException;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.TossRetryLaterException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationServiceTest {

    @Mock
    private PaymentCompensationRepository compensationRepository;
    @Mock
    private TossPaymentClient tossPaymentClient;
    @Mock
    private PaymentCompensationDlqProducer dlqProducer;
    @Mock
    private ObjectProvider<PaymentCompensationService> selfProvider;
    @InjectMocks
    private PaymentCompensationService service;

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

    // ── 실행 단계: PG 호출은 트랜잭션 밖, 결과 기록만 프록시(REQUIRES_NEW) ──

    @Test
    @DisplayName("PG 취소 호출을 먼저 하고 그 결과를 값으로 프록시의 recordResult 에 넘긴다")
    void executeCompensation_callsPgThenRecordsResultViaProxy() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation compensation = newCompensation(5);

        service.executeCompensation(compensation);

        InOrder inOrder = inOrder(tossPaymentClient, proxy);
        inOrder.verify(tossPaymentClient).cancelPayment(eq("pk-1"), anyString(), anyString());
        inOrder.verify(proxy).recordResult(compensation, null);
        // 실행 단계 자체는 장부를 건드리지 않는다 - 기록은 recordResult 만 한다
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("PG 호출 실패는 예외를 값으로 넘겨 기록하고 호출부로 전파하지 않는다")
    void executeCompensation_passesFailureAsValue() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation compensation = newCompensation(5);
        RuntimeException failure = new RuntimeException("toss down");
        given(tossPaymentClient.cancelPayment(anyString(), anyString(), anyString())).willThrow(failure);

        service.executeCompensation(compensation);

        verify(proxy).recordResult(compensation, failure);
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
    void recordResult_재시도_한도_초과로_GIVEN_UP되면_DLQ로_발행한다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        // maxRetry=3 → 3번째 실패에서 GIVEN_UP 전이
        for (int i = 0; i < 3; i++) {
            service.recordResult(compensation, new RuntimeException("toss down"));
        }

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.GIVEN_UP);
        verify(dlqProducer, times(1)).publish(any(PaymentCompensationDlqEvent.class));
    }

    @Test
    void recordResult_성공이면_SUCCEEDED_이고_DLQ로_발행하지_않는다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        service.recordResult(compensation, null);

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
        verify(dlqProducer, never()).publish(any());
    }

    @Test
    @DisplayName("429 계열(TossRetryLaterException)은 retryCount 를 올리지 않고 다음 시각만 미룬다")
    void recordResult_재시도대기_예외면_retryCount_유지한다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        service.recordResult(compensation, new TossRetryLaterException("429 too many requests"));

        assertThat(compensation.getRetryCount()).isEqualTo(0);
        verify(dlqProducer, never()).publish(any());
    }

    @Test
    @DisplayName("TossAlreadyCanceledException 은 SUCCEEDED 로 기록하고 DLQ 로 발행하지 않는다")
    void recordResult_이미취소_예외면_SUCCEEDED로_기록한다() {
        PaymentCompensation compensation = newCompensation(3);
        given(compensationRepository.save(any())).willReturn(compensation);

        service.recordResult(compensation, new TossAlreadyCanceledException("이미 취소된 결제"));

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
        verify(dlqProducer, never()).publish(any());
    }

    // ── 진입점 ──

    @Test
    @DisplayName("보상 의도 기록(프록시) → PG 호출 → 결과 기록(프록시) 순서로 진행된다")
    void intentIsRecordedBeforeExecution() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation recorded = newCompensation(5);
        given(proxy.recordCompensationIntent(any(), anyString(), any(), anyString())).willReturn(recorded);

        service.compensate(UUID.randomUUID(), "pk-1", 1000, "DB 후처리 실패");

        InOrder inOrder = inOrder(proxy, tossPaymentClient);
        inOrder.verify(proxy).recordCompensationIntent(any(), anyString(), any(), anyString());
        inOrder.verify(tossPaymentClient).cancelPayment(eq("pk-1"), anyString(), anyString());
        inOrder.verify(proxy).recordResult(recorded, null);
    }

    @Test
    @DisplayName("배치 재시도도 PG 호출 뒤 결과 기록을 프록시로 넘긴다")
    void batchRetryRecordsResultViaProxy() {
        PaymentCompensationService proxy = proxy();
        PaymentCompensation pending = newCompensation(5);
        given(proxy.acquireRetryableBatch()).willReturn(List.of(pending));

        service.retryPendingCompensations();

        verify(tossPaymentClient).cancelPayment(eq("pk-1"), anyString(), anyString());
        verify(proxy).recordResult(pending, null);
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
