package groom.backend.application.payment;

import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.payment.TossPaymentClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toss 결제 승인 후 내부 DB 처리(재고 차감, 주문 상태 변경)가 실패하면 즉시 Toss 취소 API를 호출해 자동 환불을 시도, 즉시 취소마저 실패한 건은
 * {@code payment_compensation} 테이블에 기록되고, {@link #retryPendingCompensations()} 스케줄러가 지수 백오프로 주기적으로 재시도
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCompensationService {

    private static final int RETRY_BATCH_SIZE = 50;

    private final PaymentCompensationRepository compensationRepository;
    private final TossPaymentClient tossPaymentClient;

    /**
     * 즉시 보상(환불)을 시도한다. 실패 시 재시도 대상으로 기록
     *
     * @param paymentId     내부 결제 ID
     * @param paymentKey    Toss paymentKey (이미 승인된 결제이므로 반드시 존재)
     * @param amount        승인 금액 (기록용)
     * @param failureReason 내부 DB 실패 원인
     */
    @Transactional
    public void compensate(UUID paymentId, String paymentKey, Integer amount, String failureReason) {
        PaymentCompensation compensation = PaymentCompensation.builder()
                .paymentId(paymentId)
                .paymentKey(paymentKey)
                .amount(amount)
                .reason(failureReason)
                .maxRetryCount(5)
                .build();
        compensation = compensationRepository.save(compensation);

        log.warn("[PAYMENT_COMPENSATION_START] PaymentKey: {}, Reason: {}", paymentKey, failureReason);

        executeCompensation(compensation);
    }

    /**
     * @Scheduled 배치 - 30초마다 실패한 보상 트랜잭션을 재시도
     */
    @Scheduled(fixedDelayString = "${payment.compensation.retry-delay-ms:30000}")
    public void retryPendingCompensations() {
        List<PaymentCompensation> retryables = compensationRepository.findRetryable(
                LocalDateTime.now(), RETRY_BATCH_SIZE);

        if (retryables.isEmpty()) {
            return;
        }

        log.info("[PAYMENT_COMPENSATION_BATCH] Retrying {} compensations", retryables.size());

        for (PaymentCompensation compensation : retryables) {
            try {
                executeCompensation(compensation);
            } catch (Exception e) {

                log.error("[PAYMENT_COMPENSATION_BATCH_ERROR] CompensationId: {}, Error: {}",
                        compensation.getId(), e.getMessage());
            }
        }
    }

    /**
     * Toss 취소 API 호출, 멱등성 키(paymentKey + 재시도 횟수)로 이중 환불을 방지
     */
    @Transactional
    public void executeCompensation(PaymentCompensation compensation) {
        String idempotencyKey = compensation.getPaymentKey() + ":compensate:" + compensation.getRetryCount();
        try {
            tossPaymentClient.cancelPayment(
                    compensation.getPaymentKey(),
                    "[보상 트랜잭션] " + compensation.getReason(),
                    idempotencyKey
            );

            compensation.markSucceeded();
            compensationRepository.save(compensation);

            log.info("[PAYMENT_COMPENSATION_SUCCESS] PaymentKey: {}, RetryCount: {}",
                    compensation.getPaymentKey(), compensation.getRetryCount());

        } catch (Exception e) {
            compensation.markFailed(e.getMessage());
            compensationRepository.save(compensation);

            log.error("[PAYMENT_COMPENSATION_FAILED] PaymentKey: {}, RetryCount: {}, Status: {}, Error: {}",
                    compensation.getPaymentKey(), compensation.getRetryCount(),
                    compensation.getStatus(), e.getMessage());
        }
    }
}
