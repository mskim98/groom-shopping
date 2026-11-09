package groom.backend.application.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.cart.CartApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.ProductStockService;
import groom.backend.application.product.StockDecrementer;
import groom.backend.application.raffle.RaffleTicketAllocationService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.application.raffle.RaffleValidationService;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.model.enums.PaymentStateTransition;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// @Slf4j : log 객체 생성.
@Slf4j
// @Service : 결제 유스케이스를 오케스트레이션하는 응용 서비스 빈.
@Service
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
// @Transactional(readOnly = true) : 기본은 읽기 전용. 쓰기 메서드에만 @Transactional 을 따로 붙인다.
@Transactional(readOnly = true)
public class PaymentApplicationService {

    // private final : 결제 처리에 필요한 협력 객체들. 스프링이 주입하고 외부에서 못 바꾸게 해 안전하게 사용.
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final TossPaymentClient tossPaymentClient; // 외부 Toss 결제 API 호출 클라이언트
    private final RaffleRepository raffleRepository;
    private final RaffleValidationService raffleValidationService;
    private final RaffleTicketApplicationService raffleTicketApplicationService;
    private final RaffleTicketAllocationService raffleTicketAllocationService;
    private final PaymentNotificationService paymentNotificationService;
    private final PaymentCompensationService paymentCompensationService; // 결제 후 실패 시 자동 환불
    private final PaymentOutboxRepository paymentOutboxRepository; // 결제 완료 이벤트 Outbox 적재
    private final ProductStockService productStockService; // 복원(increase) 경로 전용 - 세 전략 공통으로 낙관적 락을 쓴다
    private final StockDecrementer stockDecrementer;       // 차감 전략(stock.decrease-strategy 로 교체)
    private final ProductStockRedisRepository productStockRedisRepository; // Redis 재고 선점(결제 진입 전 게이트)
    private final ObjectMapper objectMapper; // 객체 ↔ JSON 변환
    // 자기호출(self-invocation) 시 Spring AOP 프록시를 우회해 @Transactional 이 무효화되는 문제를
    // 막기 위해, 자기 자신을 ObjectProvider 로 주입받아 프록시를 거쳐 호출한다(생성자 순환 의존 회피).
    private final ObjectProvider<PaymentApplicationService> selfProvider;

    // confirm 이 Outbox 적재 뒤 컨슈머의 결과(DONE · FAILED)를 기다리는 상한과 상태 재조회 간격.
    // 상한을 넘기면 READY 를 돌려주고 클라이언트가 GET /v1/payment/{id} 로 확인한다(컨트롤러가 202 로 답한다)
    @Value("${payment.confirm.wait-timeout-ms:10000}")
    private long confirmWaitTimeoutMs = 10000;
    @Value("${payment.confirm.poll-interval-ms:200}")
    private long confirmPollIntervalMs = 200;

    /**
     * 결제 승인.
     * <p>
     * 전체 흐름: [멱등성] paymentKey로 완료된 결제가 있는지 선조회 → 있으면 그대로 반환 (중복 승인 차단) → Redis 재고 선점 →
     * Toss Payments 승인 API 호출 (Idempotency-Key: paymentKey) → Payment READY 전이와 {@code PaymentApproved} Outbox 행을
     * 한 트랜잭션으로 커밋({@link #recordApprovedInTx}) → 컨슈머가 후처리({@link #applyApprovedEvent})를 끝내길
     * {@code payment.confirm.poll-interval-ms} 간격으로 확인하며 {@code payment.confirm.wait-timeout-ms} 까지 기다린다.
     * 그 안에 DONE 이면 DONE 을, FAILED 면 컨슈머가 기록한 실패를 예외로, 상한을 넘기면 READY 를 돌려준다.
     * <p>
     * 후처리(DONE 전이, Order CONFIRMED, 재고 차감, 티켓 발급)는 이 요청 스레드가 아니라 Kafka 컨슈머가 한다. 후처리가 실패하면
     * 컨슈머가 Toss 취소 의도를 기록하고 선점을 복원한 뒤 FAILED 로 남긴다.
     * <p>
     * 주의: 이 메서드는 {@code NOT_SUPPORTED} 로 비트랜잭션 실행된다. Toss API 호출과 결과 대기 구간 동안 DB 커넥션을 점유하지 않고,
     * READY 전이 + Outbox 적재({@link #recordApprovedInTx})만 별도의 새 트랜잭션({@code REQUIRES_NEW})으로 분리한다.
     */
    // propagation = NOT_SUPPORTED : 클래스 기본값 @Transactional(readOnly=true) 를 메서드 레벨에서 덮어써
    // 이 메서드를 '비트랜잭션'으로 실행한다. 이렇게 해야 ① Toss API 호출 · 결과 대기 동안 커넥션을 점유하지 않고
    // ② 위임 메서드의 REQUIRES_NEW 가 readOnly 트랜잭션에 합류(REQUIRED)해 쓰기가 유실되는 일을 막는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Payment confirmPayment(String paymentKey, UUID orderId, Integer amount) {
        // [1] 멱등성 선조회: 같은 paymentKey로 이미 승인이 끝난 결제면 재처리하지 않는다.
        Payment approved = paymentRepository.findByPaymentKey(paymentKey)
                .filter(Payment::isAlreadyApproved)
                .orElse(null);
        if (approved != null) {
            log.info("[PAYMENT_IDEMPOTENT_HIT] Already approved - PaymentKey: {}, Status: {}",
                    paymentKey, approved.getStatus());
            return approved;
        }

        // 결제 조회
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + orderId));

        if (payment.isAlreadyApproved()) {
            log.info("[PAYMENT_IDEMPOTENT_HIT] Order already has approved payment - OrderId: {}, Status: {}",
                    orderId, payment.getStatus());
            return payment;
        }

        // 금액 검증
        if (!payment.getAmountValue().equals(amount)) {
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다.");
        }

        // [1.5] Redis 재고 선점 - 결제 진입 직전 재고를 먼저 잡아, 부족하면 Toss 호출 없이 즉시 실패(비용 절감).
        // 미초기화 상품은 건너뛰고 DB 낙관적 락이 최종 방어한다. 이후 단계 실패 시 선점을 복원한다.
        List<PaymentApprovedEvent.ReservedItem> reservedItems = preReserveStock(orderId);

        // [2] Toss 승인 API 호출 (Idempotency-Key: paymentKey → 재시도해도 이중 결제 없음)
        TossPaymentResponse response;
        try {
            TossPaymentConfirmRequest request = new TossPaymentConfirmRequest(
                    paymentKey, orderId.toString(), amount);
            response = tossPaymentClient.confirmPayment(request, paymentKey);
            log.info("[PAYMENT_CONFIRM] Toss API response - PaymentKey: {}, Status: {}",
                    response.getPaymentKey(), response.getStatus());
        } catch (Exception e) {
            // 순서: ① 보상 의도 기록(기록하는 경우만 - 시간 초과) → ② Redis 복원(실패해도 계속) → ③ FAILED 기록
            // 시간 초과는 Toss 가 승인했는지 알 수 없다 - 돈이 빠졌을 수 있으므로 보상 의도를 먼저 남긴다.
            // 배치가 취소를 호출해 승인됐다면 환불하고, 승인된 적 없으면 404 로 NOT_APPROVED 가 되어 끝난다.
            // 4xx 거절·서킷 거절은 승인된 적이 없는 확정이라 기록하지 않는다
            if (e instanceof BusinessException timeout && timeout.getErrorCode() == ErrorCode.PAYMENT_PG_TIMEOUT) {
                try {
                    paymentCompensationService.compensate(payment.getId(), paymentKey, amount,
                            "Toss 승인 응답 시간 초과(승인 여부 불명)");
                } catch (Exception compensationError) {
                    log.error("[PAYMENT_COMPENSATION_ENTRY_FAILED] 시간 초과 보상 진입 실패(수동 대사 필요) - PaymentId: {}, PaymentKey: {}, Error: {}",
                            payment.getId(), paymentKey, compensationError.getMessage(), compensationError);
                }
            }
            // releaseStock 은 실패해도 던지지 않는다(복원 실패가 FAILED 기록과 원래 예외 전파를 막으면 안 된다)
            releaseStock(reservedItems);
            // 프록시 경유 호출이라야 REQUIRES_NEW 가 적용되어 독립 커밋된다.
            selfProvider.getObject().markPaymentFailed(payment.getId(), "TOSS_CONFIRM_FAILED", e.getMessage(), paymentKey);
            // 도메인 예외는 재포장하지 않는다 - 감싸는 순간 errorCode 가 소실돼 전역 핸들러의 catch-all(500)로 떨어진다
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new RuntimeException("결제 승인에 실패했습니다: " + e.getMessage(), e);
        }

        // [3] READY 전이 + Outbox 적재 트랜잭션 - 실패하면 롤백되고 보상 트랜잭션이 실행
        // 프록시 경유 호출이라야 @Transactional 경계가 적용된다(자기호출 우회).
        try {
            boolean recorded = selfProvider.getObject().recordApprovedInTx(payment.getId(), response, reservedItems);
            if (!recorded) {
                // 먼저 온 확정 요청이 이미 READY + Outbox 를 남겼다 - 이 요청이 선점한 Redis 재고는 중복이라 돌려준다
                log.info("[PAYMENT_IDEMPOTENT_HIT] Already recorded by earlier confirm - PaymentKey: {}", paymentKey);
                releaseStock(reservedItems);
            }
        } catch (Exception dbError) {
            // 같은 주문의 중복 확정 - 먼저 끝난 요청이 이미 READY 로 만들었거나 승인했다면(낙관적 락 · 상태 전이 거부 · 유일 제약
            // 어느 쪽으로 졌든) 이 요청은 실패가 아니라 멱등 응답이다. 여기서 보상을 기록하면 정상 결제를 취소하는 경로가 열린다
            // READY 는 READY 전이와 Outbox 적재가 한 커밋이라 후처리 이벤트가 이미 있다는 뜻이다
            Payment current = paymentRepository.findById(payment.getId()).orElse(null);
            if (current != null && (current.isAlreadyApproved() || current.getStatus() == PaymentStatus.READY)) {
                log.info("[PAYMENT_IDEMPOTENT_HIT] Concurrent confirm lost - PaymentKey: {}, Status: {}, Cause: {}",
                        paymentKey, current.getStatus(), dbError.getClass().getSimpleName());
                releaseStock(reservedItems);
                return awaitResult(current.getId());
            }
            // Toss 승인은 되었는데 READY 전이 · Outbox 적재가 실패한 경우 → 자동 환불(보상 트랜잭션) 의도 기록 + 선점 복원
            log.error("[PAYMENT_CONFIRM_DB_FAILED] OrderId: {}, PaymentKey: {}, Error: {}",
                    orderId, paymentKey, dbError.getMessage(), dbError);
            // 순서: ① 보상 의도 기록(돈이 걸린 일) → ② Redis 복원(실패해도 계속) → ③ FAILED 기록
            // 앞 단계가 실패해도 뒤 단계와 원래 예외 재던지기가 스킵되면 안 되므로 단계마다 따로 보호한다
            try {
                paymentCompensationService.compensate(
                        payment.getId(),
                        response.getPaymentKey(),
                        amount,
                        "DB 후처리 실패: " + dbError.getMessage()
                );
            } catch (Exception compensationError) {
                log.error("[PAYMENT_COMPENSATION_ENTRY_FAILED] 보상 진입 실패(수동 처리 필요) - PaymentId: {}, PaymentKey: {}, Error: {}",
                        payment.getId(), response.getPaymentKey(), compensationError.getMessage(), compensationError);
            }
            releaseStock(reservedItems);
            try {
                selfProvider.getObject().markPaymentFailed(payment.getId(), "DB_POST_PROCESS_FAILED", dbError.getMessage());
            } catch (Exception markError) {
                log.error("[PAYMENT_MARK_FAILED_ERROR] FAILED 기록 실패(결제가 PENDING 으로 남음) - PaymentId: {}, Error: {}",
                        payment.getId(), markError.getMessage(), markError);
            }
            // 도메인 예외는 재포장하지 않는다 - errorCode 가 소실되면 전역 핸들러의 catch-all(500)로 떨어진다
            if (dbError instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new RuntimeException("결제 승인 후 처리 중 실패했습니다: " + dbError.getMessage(), dbError);
        }

        // [4] 컨슈머의 후처리 결과를 기다린다
        return awaitResult(payment.getId());
    }

    // Outbox 적재 뒤 Payment 상태를 poll-interval 간격으로 다시 읽어 DONE 이면 그 결제를, FAILED 면 컨슈머가 기록한 실패를
    // 예외로, 상한을 넘기면 READY 결제를 돌려준다. 각 조회는 트랜잭션 밖(NOT_SUPPORTED)이라 매번 DB 의 최신 상태를 읽는다
    private Payment awaitResult(UUID paymentId) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(confirmWaitTimeoutMs);
        while (true) {
            Payment current = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + paymentId));
            if (current.isAlreadyApproved()) {
                return current;
            }
            if (current.getStatus() == PaymentStatus.FAILED) {
                throw postProcessFailureOf(current);
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                log.info("[PAYMENT_CONFIRM_WAIT_TIMEOUT] 대기 상한 초과, READY 로 응답 - PaymentId: {}, WaitMs: {}",
                        paymentId, confirmWaitTimeoutMs);
                return current;
            }
            try {
                Thread.sleep(Math.min(confirmPollIntervalMs, remainingMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return current;
            }
        }
    }

    // 컨슈머가 FAILED 로 남긴 failureCode 가 ErrorCode 이름이면 그 코드의 BusinessException 으로 복원한다(HTTP 상태 보존)
    // 그 밖의 코드는 기존 후처리 실패와 같은 RuntimeException 이다
    private RuntimeException postProcessFailureOf(Payment failed) {
        String code = failed.getFailureCode();
        if (code != null) {
            try {
                return new BusinessException(ErrorCode.valueOf(code));
            } catch (IllegalArgumentException notAnErrorCode) {
                // 아래 일반 실패로 처리
            }
        }
        return new RuntimeException("결제 승인 후 처리 중 실패했습니다: " + failed.getFailureMessage());
    }

    // FAILED 행에 남길 코드. 도메인 예외는 ErrorCode 이름을 그대로 남겨 대기 중인 요청이 같은 HTTP 상태로 응답하게 한다
    // 후처리 재시도를 모두 소진한 version 충돌은 예전 경로처럼 409(PRODUCT_STOCK_CONFLICT)로 답한다
    private static String failureCodeOf(Exception error) {
        if (error instanceof BusinessException business) {
            return business.getErrorCode().name();
        }
        if (error instanceof ObjectOptimisticLockingFailureException) {
            return ErrorCode.PRODUCT_STOCK_CONFLICT.name();
        }
        return "DB_POST_PROCESS_FAILED";
    }

    /**
     * Toss 승인 직후 한 트랜잭션: PENDING 인 Payment 를 READY 로 만들고(paymentKey 부착) {@code PaymentApproved} Outbox 행을 1건
     * 적재한다. 이미 READY 면(먼저 온 확정 요청이 남겼다) 아무것도 쓰지 않고 false 를 돌려줘 이벤트가 결제당 1건이게 한다.
     * DONE 전이 · 주문 확정 · 재고 차감 · 티켓 발급은 여기서 하지 않는다 - 컨슈머({@link #applyApprovedEvent})가 한다.
     * DONE 은 후처리 트랜잭션에서만 쓴다: 보상 실행({@link PaymentCompensationService})은 DONE 인 결제를 취소하지 않고
     * 건너뛰므로(SUPERSEDED) 여기서 DONE 으로 만들면 후처리 실패 때 환불 행이 환불하지 못한다.
     */
    // propagation = REQUIRES_NEW : 바깥 트랜잭션(없음 · NOT_SUPPORTED)과 무관하게 READY 와 Outbox 행을 한 커밋으로 묶는다
    // 커밋 = 이벤트 영구 보존(at-least-once). 직렬화 실패는 그대로 던져 롤백한다 - 이벤트가 후처리의 유일한 전달 수단이다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordApprovedInTx(UUID paymentId, TossPaymentResponse response,
                                      List<PaymentApprovedEvent.ReservedItem> reservedItems) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + paymentId));
        if (payment.getStatus() == PaymentStatus.READY) {
            return false;
        }
        // 상태머신은 PENDING → READY → DONE 만 허용한다. 결제창에서 paymentKey 를 받아 confirm 에 진입한 시점이 곧 READY 다
        // PENDING 이 아닌 그 밖의 상태(FAILED 등)에서는 ready() 가 전이를 거부해 던지고, 호출자가 승인된 돈을 보상 경로로 돌려보낸다
        payment.ready(response.getPaymentKey());
        paymentRepository.save(payment);
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                payment.getId(), payment.getOrder().getId(), payment.getAmountValue(), response, reservedItems);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PaymentApproved 이벤트 직렬화 실패: " + e.getMessage(), e);
        }
        paymentOutboxRepository.save(PaymentOutbox.create(payment.getId(), PaymentApprovedEvent.EVENT_TYPE, payload));
        return true;
    }

    /**
     * Kafka 컨슈머의 후처리 진입점. READY 인 결제만 {@link #applyApprovedPaymentInTx} 로 DONE · CONFIRMED · 재고 차감 ·
     * 티켓 발급까지 끝낸다. READY 가 아니면(재전달 · 동시 중복 확정) 아무것도 하지 않고 돌아와 멱등이다.
     * <p>
     * 후처리가 실패하면 ① 보상 의도 기록(돈이 걸린 일) → ② Redis 선점 복원 → ③ FAILED 기록 순으로 처리하고 예외를 던지지
     * 않는다(비즈니스 실패로는 Kafka 재전달이 없다). 결제를 읽지 못하는 DB 장애는 그대로 던져 컨슈머 오류 처리기가 재시도 ·
     * DLQ 로 보낸다. 대기 중인 confirm 요청은 FAILED 행의 failureCode 로 같은 실패를 응답한다.
     */
    // NOT_SUPPORTED : 상태 확인과 실패 처리는 각자 짧은 트랜잭션이고, 후처리 트랜잭션은 매 시도마다 REQUIRES_NEW 로 새로 연다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void applyApprovedEvent(PaymentApprovedEvent event) {
        Payment payment = paymentRepository.findById(event.paymentId()).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.READY) {
            log.info("[PAYMENT_APPROVED_SKIP] READY 가 아니라 건너뜀 - PaymentId: {}, Status: {}", event.paymentId(),
                    payment != null ? payment.getStatus() : "NOT_FOUND");
            return;
        }

        try {
            applyWithRetry(event.orderId(), event.tossResponse());
        } catch (Exception dbError) {
            handlePostProcessFailure(event, dbError);
        }
    }

    // 후처리 실패 처리. 순서와 단계별 보호는 예전 confirmPayment 의 catch 와 같다
    private void handlePostProcessFailure(PaymentApprovedEvent event, Exception dbError) {
        // 같은 결제의 중복 후처리 - 먼저 끝난 쪽이 이미 승인했다면(낙관적 락 · 상태 전이 거부 · 유일 제약 어느 쪽으로 졌든)
        // 이쪽은 실패가 아니라 멱등 종료다. 여기서 보상을 기록하면 정상 결제를 취소하는 경로가 열린다
        Payment current = paymentRepository.findById(event.paymentId()).orElse(null);
        if (current != null && current.isAlreadyApproved()) {
            log.info("[PAYMENT_IDEMPOTENT_HIT] Concurrent post-process lost - PaymentId: {}, Status: {}, Cause: {}",
                    event.paymentId(), current.getStatus(), dbError.getClass().getSimpleName());
            return;
        }
        log.error("[PAYMENT_CONFIRM_DB_FAILED] OrderId: {}, PaymentId: {}, Error: {}",
                event.orderId(), event.paymentId(), dbError.getMessage(), dbError);
        // 순서: ① 보상 의도 기록(돈이 걸린 일) → ② Redis 복원(실패해도 계속) → ③ FAILED 기록
        // 앞 단계가 실패해도 뒤 단계가 스킵되면 안 되므로 단계마다 따로 보호한다
        try {
            paymentCompensationService.compensate(
                    event.paymentId(),
                    event.tossResponse().getPaymentKey(),
                    event.amount(),
                    "DB 후처리 실패: " + dbError.getMessage()
            );
        } catch (Exception compensationError) {
            log.error("[PAYMENT_COMPENSATION_ENTRY_FAILED] 보상 진입 실패(수동 처리 필요) - PaymentId: {}, PaymentKey: {}, Error: {}",
                    event.paymentId(), event.tossResponse().getPaymentKey(), compensationError.getMessage(), compensationError);
        }
        releaseStock(event.reservedItems() != null ? event.reservedItems() : List.of());
        try {
            selfProvider.getObject().markPaymentFailed(event.paymentId(), failureCodeOf(dbError), dbError.getMessage());
        } catch (Exception markError) {
            log.error("[PAYMENT_MARK_FAILED_ERROR] FAILED 기록 실패(결제가 READY 로 남음) - PaymentId: {}, Error: {}",
                    event.paymentId(), markError.getMessage(), markError);
        }
    }

    // 후처리 트랜잭션을 version 충돌(ObjectOptimisticLockingFailureException) 시 통째로 다시 돈다. 낙관적 락 재고 전략이
    // 후처리 트랜잭션에 합류하므로 충돌 재시도는 트랜잭션 안이 아니라 이 바깥에서만 가능하다. 다른 전략에서는 Payment 의
    // lock_version 충돌(동시 중복 확정)이 여기로 오는데, 다시 돌면 이미 DONE 이라 아래 멱등 응답 경로가 받는다
    private static final int POST_PROCESS_MAX_ATTEMPTS = 3;

    private Payment applyWithRetry(UUID orderId, TossPaymentResponse response) {
        for (int attempt = 1; ; attempt++) {
            try {
                // 프록시 경유 호출이라야 @Transactional 경계가 매 시도마다 새로 열린다
                return selfProvider.getObject().applyApprovedPaymentInTx(orderId, response);
            } catch (ObjectOptimisticLockingFailureException conflict) {
                if (attempt >= POST_PROCESS_MAX_ATTEMPTS) {
                    throw conflict;
                }
                log.warn("[POST_PROCESS_RETRY] version 충돌로 후처리 재시도 - OrderId: {}, Attempt: {}", orderId, attempt);
                try {
                    // 같은 행을 두고 다투는 요청이 같은 시점에 재진입해 재충돌하지 않게 흩는다
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(10, 60));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw conflict;
                }
            }
        }
    }

    // 주문의 모든 항목에 대해 Redis 재고를 선점한다. 부족하면 이미 선점한 것을 복원하고 즉시 실패.
    // 반환: 실제 Redis 에 선점된 항목 목록(미초기화 항목은 제외 — 복원 대상이 아님).
    private List<PaymentApprovedEvent.ReservedItem> preReserveStock(UUID orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));

        List<PaymentApprovedEvent.ReservedItem> reserved = new java.util.ArrayList<>();
        for (OrderItem item : order.getOrderItems()) {
            long result = productStockRedisRepository.preReserve(item.getProductId(), item.getQuantity());
            if (result == -2L) {
                // Redis 미초기화 → 게이트 건너뜀(DB 낙관적 락이 최종 방어)
                log.warn("[STOCK_RESERVE_FALLBACK] Redis 미초기화, DB 폴백 - ProductId: {}", item.getProductId());
                continue;
            }
            if (result == -1L) {
                // 재고 부족 → 이미 선점한 항목 복원 후 즉시 실패(Toss 호출 안 함)
                releaseStock(reserved);
                log.info("[STOCK_RESERVE_OUT] ProductId: {}, Quantity: {}", item.getProductId(), item.getQuantity());
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
            }
            reserved.add(new PaymentApprovedEvent.ReservedItem(item.getProductId(), item.getQuantity()));
        }
        return reserved;
    }

    // 선점한 Redis 재고를 복원한다 (결제 실패·보상 시 Redis-DB 정합 유지).
    // 항목마다 실패를 삼키고 로그만 남긴다 - Redis 장애가 보상 의도 기록·FAILED 기록·원래 예외 전파를 막으면 안 된다.
    // 복원 못 한 선점분은 StockReconciliationJob 이 DB 재고 기준으로 되맞춘다
    private void releaseStock(List<PaymentApprovedEvent.ReservedItem> items) {
        for (PaymentApprovedEvent.ReservedItem item : items) {
            try {
                productStockRedisRepository.release(item.productId(), item.quantity());
            } catch (Exception e) {
                log.error("[STOCK_RELEASE_FAILED] Redis 선점 복원 실패 - ProductId: {}, Quantity: {}, Error: {}",
                        item.productId(), item.quantity(), e.getMessage());
            }
        }
    }

    // 주문 항목을 장바구니에서 뺄 평범한 값으로 바꾼다. 트랜잭션 안에서 불러야 한다(order 의 지연 로딩 컬렉션을 읽는다)
    private List<CartApplicationService.CartItemToRemove> cartItemsOf(Order order) {
        return order.getOrderItems().stream()
                .map(item -> new CartApplicationService.CartItemToRemove(item.getProductId(), item.getQuantity()))
                .toList();
    }

    // 트랜잭션이 있으면 커밋 뒤에, 없으면 바로 실행한다. 롤백된 후처리가 알림·장바구니 비우기를 남기지 않게 한다.
    // 커밋 뒤 작업의 실패는 이미 커밋된 결제를 되돌릴 수 없으므로 삼키고 로그만 남긴다
    private void runAfterCommit(String name, Runnable task) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("[AFTER_COMMIT_FAILED] {} - Error: {}", name, e.getMessage(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    /**
     * Toss 승인 결과를 내부 상태에 반영하는 후처리 트랜잭션 경계. Payment DONE 전이, 주문 상태 변경, 재고 차감, 티켓 발급이
     * 모두 하나의 커밋 단위에서 실행된다. 재고 차감({@link StockDecrementer#decrease})이 이 트랜잭션에 합류하므로 후처리가
     * 실패하면 DB 재고도 같은 롤백으로 되돌아온다(Redis 선점분 복원은 호출자 {@link #applyApprovedEvent} 의 releaseStock 이 맡는다).
     * 알림 · 장바구니 비우기는 커밋 뒤에만 나간다
     */
    // propagation = REQUIRES_NEW : 결제 상태 변경 + 주문 확정 + 재고 차감 + 티켓 발급을 항상
    // '독립된 새 트랜잭션'으로 묶어, 중간에 하나라도 실패하면 전부 롤백한다(All-or-Nothing).
    // 재고 차감 전략은 REQUIRED 라 이 트랜잭션에 합류한다(PessimisticLock · ConditionalUpdate 전략).
    // ✅ 자기호출 함정 해결: applyWithRetry() 가 selfProvider.getObject() 로 프록시를 거쳐 호출.
    // ✅ readOnly 합류 방지: REQUIRES_NEW 라 바깥 트랜잭션 유무와 무관하게 항상 read-write 새 트랜잭션 생성.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment applyApprovedPaymentInTx(UUID orderId, TossPaymentResponse response) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + orderId));

        String paymentMethodDetailsJson = convertPaymentMethodDetails(response);
        String receiptJson = convertObjectToJson(response.getReceipt());
        String checkoutJson = convertObjectToJson(response.getCheckout());
        LocalDateTime requestedAtDateTime = parseDateTime(response.getRequestedAt());

        // 상태머신은 PENDING → READY → DONE 만 허용한다. 결제창에서 paymentKey 를 받아 confirm 에 진입한
        // 시점이 곧 'READY' 이므로, 아직 PENDING 이면 여기서 READY 로 전이한 뒤 승인(DONE)한다.
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.ready(response.getPaymentKey());
        }

        payment.approveWithTossResponse(
                response.getPaymentKey(),
                response.getLastTransactionKey(),
                response.getBalanceAmount() != null ? response.getBalanceAmount() : response.getTotalAmount(),
                response.getSuppliedAmount() != null ? response.getSuppliedAmount() : response.getTotalAmount(),
                response.getVat() != null ? response.getVat() : 0,
                response.getTaxFreeAmount() != null ? response.getTaxFreeAmount() : 0,
                response.getTaxExemptionAmount() != null ? response.getTaxExemptionAmount() : 0,
                response.getMId(),
                response.getVersion(),
                response.getType(),
                response.getCurrency(),
                response.getUseEscrow(),
                response.getCultureExpense(),
                response.getIsPartialCancelable(),
                requestedAtDateTime,
                paymentMethodDetailsJson,
                receiptJson,
                checkoutJson
        );
        paymentRepository.save(payment);

        Order order = payment.getOrder();
        order.changeStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        List<PaymentNotificationService.StockReductionResult> stockReductions = reduceProductStock(order);
        processTicketProducts(order);

        // 성공 로그 · 알림 · 장바구니 비우기는 커밋 뒤에만 나간다. 본문에서 바로 부르면 R9 처럼 커밋이 실패한
        // 200건에도 성공 로그와 장바구니 비우기가 나가 사용자 장바구니만 비워진다
        // 커밋 뒤 콜백은 트랜잭션도 영속성 컨텍스트도 없다 - 필요한 값(사용자 id · 비울 항목)을 지금 평범한 값으로 만들어 넘긴다.
        // order 엔티티를 넘기면 지연 로딩 필드를 읽는 순간 LazyInitializationException 이다
        UUID paymentId = payment.getId();
        Long orderUserId = order.getUserId();
        List<CartApplicationService.CartItemToRemove> cartItems = cartItemsOf(order);
        runAfterCommit("PAYMENT_CONFIRM_SUCCESS", () -> {
            log.info("[PAYMENT_CONFIRM_SUCCESS] Payment confirmed - PaymentId: {}, OrderId: {}", paymentId, orderId);
            paymentNotificationService.sendStockReducedNotifications(stockReductions, orderUserId);
            paymentNotificationService.clearCartItems(orderUserId, orderId, cartItems);
        });

        return payment;
    }

    /**
     * 별도 트랜잭션으로 결제 실패 상태만 기록 (메인 트랜잭션 롤백 후에도 FAILED 상태는 남겨야 하므로 REQUIRES_NEW.)
     */
    // propagation = REQUIRES_NEW : 항상 '새 트랜잭션'을 열어 독립적으로 커밋하여,
    // 바깥 트랜잭션이 롤백돼도 'FAILED 기록'은 별도로 남긴다.
    // ✅ 자기호출 함정 해결: confirmPayment() 가 selfProvider.getObject() 로 프록시를 거쳐
    //    호출하므로 REQUIRES_NEW 가 정상 적용된다(독립 커밋 보장).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaymentFailed(UUID paymentId, String code, String message) {
        markPaymentFailed(paymentId, code, message, null);
    }

    // paymentKey : Toss 승인 실패 행에도 키를 남겨 PG 콘솔·보상 행과 대조할 수 있게 한다(PENDING 행은 키가 비어 있다)
    // 같은 클래스 안 호출이라 프록시를 안 거치지만 이미 REQUIRES_NEW 트랜잭션 안이라 상관없다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaymentFailed(UUID paymentId, String code, String message, String paymentKey) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() != PaymentStatus.DONE) {
                try {
                    p.fail(code, message);
                    p.attachPaymentKeyIfAbsent(paymentKey);
                    paymentRepository.save(p);
                } catch (IllegalStateException ignored) {
                    // 이미 종료 상태면 더 이상 전이하지 않음 (State Machine이 차단).
                }
            }
        });
    }

    /**
     * 테스트용 결제 승인 (Toss API 호출 없이)
     */
    @Transactional
    public Payment confirmPaymentForTest(UUID orderId) {
        // 결제 조회
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + orderId));

        log.info("[TEST_PAYMENT_CONFIRM] Test payment confirm start - OrderId: {}", orderId);

        // Payment 승인 처리 (테스트용 paymentKey 생성)
        String testPaymentKey = "test_" + UUID.randomUUID().toString();
        String testTransactionId = "tx_test_" + UUID.randomUUID().toString();
        // PENDING → READY → DONE 상태머신 준수 (PENDING 이면 READY 로 먼저 전이)
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.ready(testPaymentKey);
        }
        payment.approve(testPaymentKey, testTransactionId);
        paymentRepository.save(payment);

        // Order 상태 변경 (PENDING -> CONFIRMED)
        Order order = payment.getOrder();
        order.changeStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        // 재고 차감 및 차감된 상품 ID와 차감 후 재고량 수집
        List<PaymentNotificationService.StockReductionResult> stockReductions = reduceProductStock(order);

        // TICKET 카테고리 상품 처리 (Raffle 티켓 생성)
        processTicketProducts(order);

        log.info("[TEST_PAYMENT_CONFIRM_SUCCESS] Test payment confirmed - PaymentId: {}, OrderId: {}",
                payment.getId(), orderId);

        // 비동기로 알림 처리 (응답 시간에 영향 없음)
        // 차감 후 재고량을 함께 전달하여 정확한 값이 알림에 표시되도록 함
        // 주문한 사용자는 알림에서 제외
        paymentNotificationService.sendStockReducedNotifications(stockReductions, order.getUserId());

        // 비동기로 장바구니 비우기 (응답 시간에 영향 없음)
        paymentNotificationService.clearCartItems(order.getUserId(), orderId, cartItemsOf(order));

        return payment;
    }

    /**
     * 결제 취소(사용자 수동). Toss 호출은 트랜잭션 밖에서, DB 반영만 별도 트랜잭션으로 수행한다
     */
    // NOT_SUPPORTED : Toss 취소 응답을 기다리는 동안(최대 30초) DB 커넥션을 쥐지 않는다. 승인(confirmPayment)과 같은 구조
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Payment cancelPayment(UUID paymentId, String cancelReason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + paymentId));
        // Toss 를 부르기 전에 취소 가능 상태인지 본다 - 부른 뒤에 상태 전이가 거부되면 Toss 만 취소된 불일치가 남는다
        PaymentStateTransition.validate(payment.getStatus(), PaymentStatus.CANCELED);

        String paymentKey = payment.getPaymentKeyValue();
        try {
            // 3인자 메서드를 직접 불러야 프록시의 @CircuitBreaker(toss-cancel) 가 적용된다.
            // 2인자 default 메서드는 같은 객체 안에서 3인자를 부르는 자기 호출이라 서킷을 우회한다
            tossPaymentClient.cancelPayment(paymentKey, cancelReason, paymentKey + ":cancel");
        } catch (BusinessException e) {
            // 서킷 OPEN 같은 도메인 예외는 재포장하지 않는다 - errorCode(503)를 잃으면 500 으로 나간다
            log.error("[PAYMENT_CANCEL_FAILED] Payment cancel failed - PaymentId: {}, Error: {}", paymentId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[PAYMENT_CANCEL_FAILED] Payment cancel failed - PaymentId: {}, Error: {}", paymentId, e.getMessage());
            throw new RuntimeException("결제 취소에 실패했습니다: " + e.getMessage(), e);
        }

        try {
            // 프록시 경유라야 REQUIRES_NEW 가 적용된다
            return selfProvider.getObject().applyCancelInTx(paymentId);
        } catch (Exception dbError) {
            // Toss 는 취소됐는데 DB 는 DONE 인 상태 - 조용히 남지 않도록 보상 장부에 대사 행을 남긴다
            log.error("[PAYMENT_CANCEL_DB_UPDATE_FAILED] Toss 취소 성공 뒤 DB 갱신 실패(대사 필요) - PaymentId: {}, PaymentKey: {}, Error: {}",
                    paymentId, paymentKey, dbError.getMessage(), dbError);
            try {
                paymentCompensationService.recordReconcileNeeded(paymentId, paymentKey, payment.getAmountValue(),
                        "[RECONCILE_MANUAL_CANCEL] Toss 취소 성공, DB 갱신 실패: " + dbError.getMessage());
            } catch (Exception recordError) {
                log.error("[PAYMENT_CANCEL_RECONCILE_ENTRY_FAILED] 대사 행 기록 실패(수동 처리 필요) - PaymentId: {}, Error: {}",
                        paymentId, recordError.getMessage(), recordError);
            }
            throw new RuntimeException("결제 취소에 실패했습니다: " + dbError.getMessage(), dbError);
        }
    }

    /**
     * Toss 취소 성공 뒤의 DB 반영 - 결제 취소, 주문 취소, 재고 복구를 한 트랜잭션으로 묶는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment applyCancelInTx(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + paymentId));

        // Payment 취소 처리
        payment.cancel();
        paymentRepository.save(payment);

        // Order 상태 변경 (CONFIRMED -> CANCELLED)
        Order order = payment.getOrder();
        order.changeStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // 재고 복구
        restoreProductStock(order);

        log.info("[PAYMENT_CANCEL_SUCCESS] Payment cancelled - PaymentId: {}", paymentId);
        return payment;
    }

    /**
     * 결제 조회
     */
    public Payment getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + paymentId));
    }

    /**
     * 주문의 결제 조회
     */
    public Payment getPaymentByOrderId(UUID orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문의 결제를 찾을 수 없습니다: " + orderId));
    }

    /**
     * 사용자의 결제 목록 조회
     */
    public List<Payment> getPaymentsByUserId(Long userId) {
        return paymentRepository.findByUserId(userId);
    }

    // === 비공개 메서드 ===

    /**
     * Toss Payment API 응답 데이터로부터 결제 수단 상세정보를 JSON 문자열로 변환
     */
    private String convertPaymentMethodDetails(TossPaymentResponse response) {
        try {
            // 결제 수단별로 데이터 구성
            if (response.getCard() != null) {
                return convertObjectToJson(response.getCard());
            } else if (response.getVirtualAccount() != null) {
                return convertObjectToJson(response.getVirtualAccount());
            } else if (response.getTransfer() != null) {
                return convertObjectToJson(response.getTransfer());
            } else if (response.getMobilePhone() != null) {
                return convertObjectToJson(response.getMobilePhone());
            } else if (response.getGiftCertificate() != null) {
                return convertObjectToJson(response.getGiftCertificate());
            } else if (response.getEasyPay() != null) {
                return convertObjectToJson(response.getEasyPay());
            }
            return null;
        } catch (Exception e) {
            log.warn("[PAYMENT_METHOD_DETAILS_CONVERT] Failed to convert payment method details", e);
            return null;
        }
    }

    /**
     * Object를 JSON 문자열로 변환
     */
    private String convertObjectToJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            if (object instanceof String) {
                return (String) object;
            }
            // ObjectMapper를 사용해서 Object를 JSON 문자열로 변환
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            log.warn("[OBJECT_TO_JSON_CONVERT] Failed to convert object to JSON", e);
            return null;
        }
    }

    /**
     * ISO 8601 형식의 날짜 문자열을 LocalDateTime으로 파싱 예: "2022-06-08T15:40:49+09:00" -> LocalDateTime
     */
    private LocalDateTime parseDateTime(String dateTimeString) {
        if (dateTimeString == null || dateTimeString.isEmpty()) {
            return null;
        }

        try {
            // ISO 8601 형식 (예: "2022-06-08T15:40:49+09:00")
            OffsetDateTime offsetDateTime = OffsetDateTime.parse(dateTimeString);
            return offsetDateTime.toLocalDateTime();
        } catch (Exception e1) {
            try {
                // 다른 형식 시도 (예: "2022-06-08T15:40:49")
                return LocalDateTime.parse(dateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception e2) {
                log.warn("[DATETIME_PARSE] Failed to parse datetime: {}", dateTimeString, e2);
                return null;
            }
        }
    }

    private List<PaymentNotificationService.StockReductionResult> reduceProductStock(Order order) {
        List<PaymentNotificationService.StockReductionResult> results = new java.util.ArrayList<>();

        // 차감은 호출한 트랜잭션(후처리 REQUIRES_NEW 또는 테스트용 @Transactional)에 합류한다. 중간에 한 건이 실패하면
        // 앞선 차감까지 같은 롤백으로 되돌아오므로 별도 복원 코드를 두지 않는다(복원을 REQUIRES_NEW 로 하면 바깥 트랜잭션이
        // 쥔 행 락을 기다려 교착한다)
        // 상품 id 오름차순으로 잠가, 서로 다른 순서로 여러 상품을 담은 두 주문이 서로의 행 락을 기다리는 교착을 막는다
        List<OrderItem> items = order.getOrderItems().stream()
                .sorted(java.util.Comparator.comparing(OrderItem::getProductId))
                .toList();
        for (OrderItem orderItem : items) {
            // 선택된 전략으로 재고 차감 (동시 결제의 Lost Update 차단). 차감 후 재고량을 반환.
            int stockAfter = stockDecrementer.decrease(orderItem.getProductId(), orderItem.getQuantity());

            // 차감된 상품 ID와 차감 후 재고량 저장
            results.add(new PaymentNotificationService.StockReductionResult(orderItem.getProductId(), stockAfter));

            log.info("[STOCK_REDUCE] Product stock reduced - ProductId: {}, Quantity: {}, StockAfter: {}",
                    orderItem.getProductId(), orderItem.getQuantity(), stockAfter);
        }
        return results;
    }

    private void restoreProductStock(Order order) {
        for (OrderItem orderItem : order.getOrderItems()) {
            // 낙관적 락 + 자동 재시도 경로로 통일(기존 직접 findById→increaseStock→save 는 @Version 충돌 시 재시도 없이 실패).
            productStockService.increaseWithOptimisticLock(orderItem.getProductId(), orderItem.getQuantity());

            // Redis 선점 재고도 함께 복원해 Redis-DB 드리프트를 막는다.
            productStockRedisRepository.release(orderItem.getProductId(), orderItem.getQuantity());

            log.info("[STOCK_RESTORE] Product stock restored - ProductId: {}, Quantity: {}",
                    orderItem.getProductId(), orderItem.getQuantity());
        }
    }

    private void processTicketProducts(Order order) {
        Long userId = order.getUserId();

        for (OrderItem orderItem : order.getOrderItems()) {
            // 상품 조회
            Product product = productRepository.findById(orderItem.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

            // TICKET 카테고리가 아니면 건너뛰기
            if (product.getCategory() != ProductCategory.TICKET) {
                continue;
            }

            log.info("[TICKET_PRODUCT_PROCESS] TICKET product detected - ProductId: {}, UserId: {}, Quantity: {}",
                    product.getId(), userId, orderItem.getQuantity());

            // 상품ID로 Raffle 조회
            Raffle raffle = raffleRepository.findByRaffleProductId(product.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "해당 상품에 대한 추첨 정보를 찾을 수 없습니다: " + product.getId()));

            // 추첨 상태 검증 (응모 가능 여부)
            raffleValidationService.validateRaffleForEntry(raffle);

            // 수량만큼 티켓 생성
            int quantity = orderItem.getQuantity();

            // 사용자 응모 한도 검증 (전체 수량에 대해 한 번만)
            raffleValidationService.validateUserEntryLimit(raffle, userId, quantity);

            // 티켓생성
            raffleTicketApplicationService.createTickets(raffle, userId, quantity);
        }
    }
}