package groom.backend.application.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.raffle.RaffleTicketAllocationService;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.application.raffle.RaffleValidationService;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentApplicationService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final TossPaymentClient tossPaymentClient;
    private final RaffleRepository raffleRepository;
    private final RaffleValidationService raffleValidationService;
    private final RaffleTicketApplicationService raffleTicketApplicationService;
    private final RaffleTicketAllocationService raffleTicketAllocationService;
    private final PaymentNotificationService paymentNotificationService;
    private final ObjectMapper objectMapper;

    /**
     * 결제 승인 - Toss Payments API 호출 후 상태 변경
     */
    @Transactional
    public Payment confirmPayment(String paymentKey, UUID orderId, Integer amount) {
        // 결제 조회
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + orderId));

        // 금액 검증
        if (!payment.getAmountValue().equals(amount)) {
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다.");
        }

        try {
            // Toss Payments API 결제 승인 요청
            TossPaymentConfirmRequest request = new TossPaymentConfirmRequest(
                    paymentKey,
                    orderId.toString(),
                    amount
            );
            TossPaymentResponse response = tossPaymentClient.confirmPayment(request);

            log.info("[PAYMENT_CONFIRM] Toss API response - PaymentKey: {}, Status: {}",
                    response.getPaymentKey(), response.getStatus());

            // Toss Payment API 응답 데이터로 Payment 승인 처리
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

            // Order 상태 변경 (PENDING -> CONFIRMED)
            Order order = payment.getOrder();
            order.changeStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);

            // 재고 차감 및 차감된 상품 ID 수집
            List<UUID> reducedProductIds = reduceProductStock(order);

            // TICKET 카테고리 상품 처리 (Raffle 티켓 생성)
            processTicketProducts(order);

            log.info("[PAYMENT_CONFIRM_SUCCESS] Payment confirmed - PaymentId: {}, OrderId: {}",
                    payment.getId(), orderId);

            // 비동기로 알림 처리 (응답 시간에 영향 없음)
            paymentNotificationService.sendStockReducedNotifications(reducedProductIds);

            // 비동기로 장바구니 비우기 (응답 시간에 영향 없음)
            paymentNotificationService.clearCartItems(order);

            return payment;

        } catch (Exception e) {
            // 결제 실패 처리
            payment.fail("PAYMENT_FAILED", e.getMessage());
            paymentRepository.save(payment);

            log.error("[PAYMENT_CONFIRM_FAILED] Payment failed - OrderId: {}, Error: {}",
                    orderId, e.getMessage());

            throw new RuntimeException("결제 승인에 실패했습니다: " + e.getMessage(), e);
        }
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

        // 재고 차감 및 차감된 상품 ID 수집
        List<UUID> reducedProductIds = reduceProductStock(order);

        // TICKET 카테고리 상품 처리 (Raffle 티켓 생성)
        processTicketProducts(order);

        log.info("[TEST_PAYMENT_CONFIRM_SUCCESS] Test payment confirmed - PaymentId: {}, OrderId: {}",
                payment.getId(), orderId);

        // 비동기로 알림 처리 (응답 시간에 영향 없음)
        paymentNotificationService.sendStockReducedNotifications(reducedProductIds);

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

    private List<UUID> reduceProductStock(Order order) {
        List<UUID> reducedProductIds = new java.util.ArrayList<>();

        for (OrderItem orderItem : order.getOrderItems()) {
            Product product = productRepository.findById(orderItem.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

            product.decreaseStock(orderItem.getQuantity());
            productRepository.save(product);

            // 차감된 상품 ID 수집
            reducedProductIds.add(product.getId());

            log.info("[STOCK_REDUCE] Product stock reduced - ProductId: {}, Quantity: {}, Remaining: {}",
                    product.getId(), orderItem.getQuantity(), product.getStock());
        }

        return reducedProductIds;
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