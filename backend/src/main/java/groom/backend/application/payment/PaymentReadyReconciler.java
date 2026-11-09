package groom.backend.application.payment;

import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// READY 에 멈춘 결제의 안전망. confirm 이 READY + Outbox 를 남긴 뒤 컨슈머가 후처리를 끝내지 못하면(DLQ 로 간 레코드 · 오래 이어진
// Kafka 장애 · 보상은 남겼는데 FAILED 기록이 유실) 결제는 Toss 승인만 된 채 주문도 환불도 없이 READY 로 남는다. 이를 되찾는다
// 한 결제의 처리는 각자 짧은 트랜잭션(저장소 호출 · REQUIRES_NEW 서비스 메서드)이라 한 건의 실패가 다른 건을 롤백하지 않는다
// shortcut: 인스턴스가 여럿이면 같은 결제를 동시에 대사할 수 있다(보상은 payment_id 유일 제약이 막고 재발행 횟수는 한 번 더 오를 수 있다), 다중 인스턴스 배포 때 분산 잠금을 둔다
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReadyReconciler {

    private static final int BATCH_SIZE = 100;
    private static final String FAILURE_CODE = "DB_POST_PROCESS_FAILED";

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final PaymentCompensationRepository paymentCompensationRepository;
    private final PaymentCompensationService paymentCompensationService;
    private final PaymentApplicationService paymentApplicationService;

    // 이만큼 READY 로 머문 결제만 본다. 확정 대기 상한(10초)보다 훨씬 길어야 대기 중인 요청의 결제를 건드리지 않는다
    @Value("${payment.reconcile.stale-after-ms:300000}")
    private long staleAfterMs = 300000;
    @Value("${payment.reconcile.max-republish:2}")
    private int maxRepublish = 2;

    @Scheduled(fixedDelayString = "${payment.reconcile.delay-ms:60000}")
    public void reconcileStaleReady() {
        LocalDateTime cutoff = LocalDateTime.now().minusNanos(staleAfterMs * 1_000_000);
        List<Payment> stale = paymentRepository.findStaleReady(cutoff, BATCH_SIZE);
        for (Payment payment : stale) {
            try {
                reconcile(payment);
            } catch (Exception e) {
                // 한 건의 실패가 나머지 대사를 막지 않는다. 다음 회차에 다시 본다
                log.error("[PAYMENT_READY_RECONCILE] 대사 실패(다음 회차에 재시도) - PaymentId: {}, Error: {}",
                        payment.getId(), e.getMessage(), e);
            }
        }
    }

    private void reconcile(Payment payment) {
        // ① 보상 행이 있다 - 환불은 보상 배치 몫이다. 결제가 READY 로 남은 표시 누락만 바로잡는다
        if (paymentCompensationRepository.findByPaymentId(payment.getId()).isPresent()) {
            log.warn("[PAYMENT_READY_RECONCILE] branch=COMPENSATION_EXISTS, FAILED 표시만 복구 - PaymentId: {}", payment.getId());
            paymentApplicationService.markPaymentFailed(payment.getId(), FAILURE_CODE,
                    "대사: 보상 행 존재, FAILED 표시 누락 복구");
            return;
        }

        Optional<PaymentOutbox> outbox = paymentOutboxRepository
                .findByAggregateIdAndEventType(payment.getId(), PaymentApprovedEvent.EVENT_TYPE);

        // ② 아직 발행 전이다 - 폴러가 보낼 것이다
        if (outbox.isPresent() && outbox.get().getStatus() == PaymentOutbox.OutboxStatus.PENDING) {
            log.info("[PAYMENT_READY_RECONCILE] branch=OUTBOX_PENDING, 건너뜀 - PaymentId: {}", payment.getId());
            return;
        }

        // ③ 발행은 됐는데 후처리가 안 끝났다 - 상한까지 다시 내보낸다(컨슈머는 Payment 상태로 멱등)
        if (outbox.isPresent() && outbox.get().getRepublishCount() < maxRepublish) {
            PaymentOutbox row = outbox.get();
            row.republish();
            paymentOutboxRepository.save(row);
            log.warn("[PAYMENT_READY_RECONCILE] branch=REPUBLISH, 재발행 {}/{} - PaymentId: {}",
                    row.getRepublishCount(), maxRepublish, payment.getId());
            return;
        }

        // ④ 재발행 상한에 닿았거나 outbox 행이 없다 - Toss 승인은 됐는데 후처리가 영영 안 끝난다. 환불 의도를 남기고 FAILED 로 닫는다
        log.error("[PAYMENT_READY_RECONCILE] branch=COMPENSATE, 후처리 미완료로 환불 의도 기록 - PaymentId: {}, OutboxExists: {}",
                payment.getId(), outbox.isPresent());
        paymentCompensationService.compensate(payment.getId(), payment.getPaymentKeyValue(), payment.getAmountValue(),
                "대사: 후처리 미완료(READY 로 " + staleAfterMs + "ms 이상 머묾)");
        paymentApplicationService.markPaymentFailed(payment.getId(), FAILURE_CODE,
                "대사: 후처리 미완료로 환불 처리");
    }
}
