package groom.backend.application.payment;

import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.kafka.PaymentCompensationDlqProducer;
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
// @Slf4j : log 객체 생성.
@Slf4j
// @Service : 보상(환불) 유스케이스를 담당하는 응용 서비스 빈.
@Service
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
public class PaymentCompensationService {

    private static final int RETRY_BATCH_SIZE = 50;

    // private final : 스프링이 주입하는 협력 객체. 외부에서 못 바꾸게 막아 안전하게 사용.
    private final PaymentCompensationRepository compensationRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentCompensationDlqProducer dlqProducer; // 최종 실패(GIVEN_UP) 시 DLQ 발행

    /**
     * 즉시 보상(환불)을 시도한다. 실패 시 재시도 대상으로 기록
     *
     * @param paymentId     내부 결제 ID
     * @param paymentKey    Toss paymentKey (이미 승인된 결제이므로 반드시 존재)
     * @param amount        승인 금액 (기록용)
     * @param failureReason 내부 DB 실패 원인
     */
    // @Transactional : 보상 기록 저장(save)을 트랜잭션으로 보장한다.
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
    // @Scheduled(fixedDelayString) : 사용자 요청과 무관하게 스프링이 주기적으로 자동 호출한다.
    // 직전 실행이 끝난 뒤 지정 시간(기본 30초) 뒤 다시 실행 → 일시적 네트워크 오류로 못한 환불을 재시도.
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
     * Toss 취소 API 호출, 멱등성 키(paymentKey + 보상 레코드 ID)로 이중 환불을 방지
     */
    // @Transactional : 환불 결과(성공/실패)에 따른 상태 변경 저장을 트랜잭션으로 보장한다.
    @Transactional
    public void executeCompensation(PaymentCompensation compensation) {
        // 멱등성 키 : 보상 레코드 ID 는 재시도 간 불변이라 같은 취소 의도가 항상 같은 키로 도달한다
        // retryCount 를 섞으면 시도마다 키가 달라져, Toss 는 이미 처리했는데 응답만 유실된 경우를
        // 새 요청으로 오인해 이중 환불이 난다 -> 멱등키가 목적을 잃는다
        String idempotencyKey = compensation.getPaymentKey() + ":compensate:" + compensation.getId();
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

            // 재시도 한도 초과로 최종 실패(GIVEN_UP)면 DLQ 로 이관해 운영팀이 즉시 인지하도록 한다.
            if (compensation.getStatus() == CompensationStatus.GIVEN_UP) {
                dlqProducer.publish(new PaymentCompensationDlqEvent(
                        compensation.getId(),
                        compensation.getPaymentId(),
                        compensation.getPaymentKey(),
                        compensation.getAmount(),
                        compensation.getReason(),
                        e.getMessage(),
                        compensation.getRetryCount(),
                        LocalDateTime.now()));
            }
        }
    }
}
