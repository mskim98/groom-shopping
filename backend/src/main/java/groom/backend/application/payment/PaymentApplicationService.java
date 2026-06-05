package groom.backend.application.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentCompletedEvent;
import groom.backend.application.raffle.RaffleTicketAllocationService;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.application.raffle.RaffleValidationService;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentOutbox;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper; // 객체 ↔ JSON 변환
    // 자기호출(self-invocation) 시 Spring AOP 프록시를 우회해 @Transactional 이 무효화되는 문제를
    // 막기 위해, 자기 자신을 ObjectProvider 로 주입받아 프록시를 거쳐 호출한다(생성자 순환 의존 회피).
    private final ObjectProvider<PaymentApplicationService> selfProvider;

    /**
     * 결제 승인.
     * <p>
     * 전체 흐름: [멱등성] paymentKey로 완료된 결제가 있는지 선조회 → 있으면 그대로 반환 (중복 승인 차단) Toss Payments 승인 API 호출 (Idempotency-Key:
     * paymentKey) DB 후처리(Payment DONE 전이, Order CONFIRMED, 재고 차감, 티켓 발급)를 하나의 트랜잭션으로 DB 후처리 실패 시 Toss 취소 API로 자동 환불(보상
     * 트랜잭션). 취소도 실패하면 재시도 테이블에 기록
     * <p>
     * <p>
     * 주의: 이 메서드는 {@code NOT_SUPPORTED} 로 비트랜잭션 실행된다. Toss API 호출 구간 동안 DB 커넥션을 점유하지 않고,
     * DB 후처리({@link #applyApprovedPaymentInTx})만 별도의 새 트랜잭션({@code REQUIRES_NEW})으로 분리해 "외부 승인 + 내부 실패"
     * 부분 실패를 정확히 탐지·보상할 수 있게 한다.
     */
    // propagation = NOT_SUPPORTED : 클래스 기본값 @Transactional(readOnly=true) 를 메서드 레벨에서 덮어써
    // 이 메서드를 '비트랜잭션'으로 실행한다. 이렇게 해야 ① Toss API 호출 동안 커넥션을 점유하지 않고
    // ② 위임 메서드의 REQUIRES_NEW 가 readOnly 트랜잭션에 합류(REQUIRED)해 쓰기가 유실되는 일을 막는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Payment confirmPayment(String paymentKey, UUID orderId, Integer amount) {
        // [1] 멱등성 선조회: 같은 paymentKey로 이미 승인이 끝난 결제면 재처리하지 않는다.
        var existing = paymentRepository.findByPaymentKey(paymentKey);
        if (existing.isPresent() && existing.get().isAlreadyApproved()) {
            log.info("[PAYMENT_IDEMPOTENT_HIT] Already approved - PaymentKey: {}, Status: {}",
                    paymentKey, existing.get().getStatus());
            return existing.get();
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

        // [2] Toss 승인 API 호출 (Idempotency-Key: paymentKey → 재시도해도 이중 결제 없음)
        TossPaymentResponse response;
        try {
            TossPaymentConfirmRequest request = new TossPaymentConfirmRequest(
                    paymentKey, orderId.toString(), amount);
            response = tossPaymentClient.confirmPayment(request, paymentKey);
            log.info("[PAYMENT_CONFIRM] Toss API response - PaymentKey: {}, Status: {}",
                    response.getPaymentKey(), response.getStatus());
        } catch (Exception e) {
            // Toss 승인 자체가 실패 → 외부 승인 없음. DB 상태만 FAILED로 기록 (보상 불필요)
            // 프록시 경유 호출이라야 REQUIRES_NEW 가 적용되어 독립 커밋된다.
            selfProvider.getObject().markPaymentFailed(payment.getId(), "TOSS_CONFIRM_FAILED", e.getMessage());
            throw new RuntimeException("결제 승인에 실패했습니다: " + e.getMessage(), e);
        }

        // [3] DB 후처리 트랜잭션 - 실패하면 롤백되고 보상 트랜잭션이 실행
        // 프록시 경유 호출이라야 @Transactional 경계가 적용된다(자기호출 우회).
        try {
            return selfProvider.getObject().applyApprovedPaymentInTx(orderId, response);
        } catch (Exception dbError) {
            // [4] Toss 승인은 되었는데 내부 DB 처리가 실패한 경우 → 자동 환불(보상 트랜잭션)
            log.error("[PAYMENT_CONFIRM_DB_FAILED] OrderId: {}, PaymentKey: {}, Error: {}",
                    orderId, paymentKey, dbError.getMessage(), dbError);
            paymentCompensationService.compensate(
                    payment.getId(),
                    response.getPaymentKey(),
                    amount,
                    "DB 후처리 실패: " + dbError.getMessage()
            );
            selfProvider.getObject().markPaymentFailed(payment.getId(), "DB_POST_PROCESS_FAILED", dbError.getMessage());
            throw new RuntimeException("결제 승인 후 처리 중 실패했습니다: " + dbError.getMessage(), dbError);
        }
    }

    /**
     * Toss 승인 결과를 내부 상태에 반영하는 트랜잭션 경계. 재고 차감, 주문 상태 변경, 티켓 발급이 모두 하나의 커밋 단위에서 실행
     */
    // propagation = REQUIRES_NEW : 결제 상태 변경 + 주문 확정 + 재고 차감 + 티켓 발급을 항상
    // '독립된 새 트랜잭션'으로 묶어, 중간에 하나라도 실패하면 전부 롤백한다(All-or-Nothing).
    // ✅ 자기호출 함정 해결: confirmPayment() 가 selfProvider.getObject() 로 프록시를 거쳐 호출.
    // ✅ readOnly 합류 방지: REQUIRES_NEW 라 바깥 트랜잭션 유무와 무관하게 항상 read-write 새 트랜잭션 생성.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment applyApprovedPaymentInTx(UUID orderId, TossPaymentResponse response) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + orderId));

        String paymentMethodDetailsJson = convertPaymentMethodDetails(response);
        String receiptJson = convertObjectToJson(response.getReceipt());
        String checkoutJson = convertObjectToJson(response.getCheckout());
        LocalDateTime requestedAtDateTime = parseDateTime(response.getRequestedAt());

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

        log.info("[PAYMENT_CONFIRM_SUCCESS] Payment confirmed - PaymentId: {}, OrderId: {}",
                payment.getId(), orderId);

        // [Outbox] 결제 완료 이벤트를 '같은 트랜잭션'으로 적재 → 커밋 = 이벤트 영구 보존(at-least-once)
        // 별도 publisher 가 Kafka 로 발행. 알림은 아직 아래 직접 호출도 유지(Strangler 점진 전환).
        appendPaymentCompletedOutbox(payment);

        paymentNotificationService.sendStockReducedNotifications(stockReductions, order);
        paymentNotificationService.clearCartItems(order);

        return payment;
    }

    // 결제 완료 이벤트를 Outbox 테이블에 적재한다. 호출 트랜잭션(REQUIRES_NEW)에 합류해 결제와 원자적으로 커밋된다.
    private void appendPaymentCompletedOutbox(Payment payment) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                payment.getId(), payment.getOrder().getId(), payment.getAmountValue(), LocalDateTime.now());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 직렬화 실패(사실상 발생 불가)는 결제 자체를 막지 않도록 로깅만 한다.
            log.error("[OUTBOX_APPEND_SKIP] paymentId={}, error={}", payment.getId(), e.getMessage());
            return;
        }
        // DB 저장 실패는 그대로 전파 → 트랜잭션 롤백(결제와 이벤트의 원자성 보장)
        paymentOutboxRepository.save(
                PaymentOutbox.create(payment.getId(), PaymentCompletedEvent.EVENT_TYPE, payload));
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
        paymentRepository.findById(paymentId).ifPresent(p -> {
            if (p.getStatus() != PaymentStatus.DONE) {
                try {
                    p.fail(code, message);
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
        paymentNotificationService.sendStockReducedNotifications(stockReductions, order);

        // 비동기로 장바구니 비우기 (응답 시간에 영향 없음)
        paymentNotificationService.clearCartItems(order);

        return payment;
    }

    /**
     * 결제 취소
     */
    @Transactional
    public Payment cancelPayment(UUID paymentId, String cancelReason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + paymentId));

        try {
            // Toss Payments API 결제 취소 요청
            TossPaymentResponse response = tossPaymentClient.cancelPayment(
                    payment.getPaymentKeyValue(),
                    cancelReason
            );

            // Payment 취소 처리
            payment.cancel();
            paymentRepository.save(payment);

            // Order 상태 변경 (CONFIRMED -> CANCELLED)
            Order order = payment.getOrder();
            order.changeStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);

            // 재고 복구
            restoreProductStock(order);

            log.info("[PAYMENT_CANCEL_SUCCESS] Payment cancelled - PaymentId: {}, Reason: {}",
                    paymentId, cancelReason);

            return payment;

        } catch (Exception e) {
            log.error("[PAYMENT_CANCEL_FAILED] Payment cancel failed - PaymentId: {}, Error: {}",
                    paymentId, e.getMessage());
            throw new RuntimeException("결제 취소에 실패했습니다: " + e.getMessage(), e);
        }
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

        for (OrderItem orderItem : order.getOrderItems()) {
            Product product = productRepository.findById(orderItem.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

            // 재고 차감 전 값 저장
            int stockBefore = product.getStock();

            product.decreaseStock(orderItem.getQuantity());
            productRepository.save(product);

            // 차감 후 재고량 확인 (차감 후 값)
            int stockAfter = product.getStock();

            // 차감된 상품 ID와 차감 후 재고량 저장
            results.add(new PaymentNotificationService.StockReductionResult(product.getId(), stockAfter));

            log.info(
                    "[STOCK_REDUCE] Product stock reduced - ProductId: {}, Quantity: {}, StockBefore: {}, StockAfter: {}",
                    product.getId(), orderItem.getQuantity(), stockBefore, stockAfter);
        }

        return results;
    }

    private void restoreProductStock(Order order) {
        for (OrderItem orderItem : order.getOrderItems()) {
            Product product = productRepository.findById(orderItem.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

            product.increaseStock(orderItem.getQuantity());
            productRepository.save(product);

            log.info("[STOCK_RESTORE] Product stock restored - ProductId: {}, Quantity: {}, Current: {}",
                    product.getId(), orderItem.getQuantity(), product.getStock());
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