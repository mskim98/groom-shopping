package groom.backend.application.payment;

import groom.backend.application.raffle.RaffleTicketAllocationService;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.application.raffle.RaffleValidationService;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.domain.raffle.entity.Raffle;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
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

    /**
     * 결제 준비 - Order 기반으로 Payment 생성
     */
    @Transactional
    public Payment preparePayment(UUID orderId, PaymentMethod paymentMethod) {
        // 주문 조회
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));

        // 이미 결제가 존재하는지 확인
        if (paymentRepository.existsByOrderId(orderId)) {
            throw new IllegalStateException("이미 결제가 생성된 주문입니다: " + orderId);
        }

        // 주문명 생성 (첫 번째 상품명 + 외 N건)
        List<OrderItem> orderItems = order.getOrderItems();
        String orderName = createOrderName(orderItems);

        // Payment 생성
        Payment payment = Payment.builder()
                .order(order)
                .userId(order.getUserId())
                .amount(order.getTotalAmount())
                .orderName(orderName)
                .method(paymentMethod)
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        // Order에 Payment 연결
        order.assignPayment(savedPayment);
        orderRepository.save(order);

        log.info("[PAYMENT_PREPARE] Payment created - PaymentId: {}, OrderId: {}, Amount: {}",
                savedPayment.getId(), orderId, savedPayment.getAmountValue());

        return savedPayment;
    }

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

            // Payment 승인 처리
            payment.approve(response.getPaymentKey(), response.getTransactionKey());
            paymentRepository.save(payment);

            // Order 상태 변경 (PENDING -> CONFIRMED)
            Order order = payment.getOrder();
            order.changeStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);

            // 재고 차감
            reduceProductStock(order);

            // TICKET 카테고리 상품 처리 (Raffle 티켓 생성)
            processTicketProducts(order);

            log.info("[PAYMENT_CONFIRM_SUCCESS] Payment confirmed - PaymentId: {}, OrderId: {}",
                    payment.getId(), orderId);

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

        // TICKET 카테고리 상품 처리 (Raffle 티켓 생성)
        processTicketProducts(order);

        // 재고 차감
        reduceProductStock(order);

        log.info("[TEST_PAYMENT_CONFIRM_SUCCESS] Test payment confirmed - PaymentId: {}, OrderId: {}",
                payment.getId(), orderId);

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

    private String createOrderName(List<OrderItem> orderItems) {
        if (orderItems.isEmpty()) {
            return "주문";
        }

        OrderItem firstItem = orderItems.get(0);
        if (orderItems.size() == 1) {
            return firstItem.getProductName();
        }

        return firstItem.getProductName() + " 외 " + (orderItems.size() - 1) + "건";
    }

    private void reduceProductStock(Order order) {
        for (OrderItem orderItem : order.getOrderItems()) {
            Product product = productRepository.findById(orderItem.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

            product.decreaseStock(orderItem.getQuantity());
            productRepository.save(product);

            log.info("[STOCK_REDUCE] Product stock reduced - ProductId: {}, Quantity: {}, Remaining: {}",
                    product.getId(), orderItem.getQuantity(), product.getStock());
        }
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

            // 티켓 번호 범위 할당 (Pessimistic Lock으로 동시성 제어)
            Long startTicketNumber = 1l;

            log.info(
                    "[TICKET_NUMBER_ALLOCATED] Ticket number range allocated - RaffleId: {}, StartNumber: {}, Count: {}",
                    raffle.getRaffleId(), startTicketNumber, quantity);

            // 할당된 번호로 티켓 생성
            for (int i = 0; i < quantity; i++) {
                Long ticketNumber = startTicketNumber + i;

                // 각 티켓 생성 전 응모 한도 재검증 (동시성 제어)
                raffleValidationService.validateUserEntryLimit(raffle, userId, 1);

                Boolean created =true;

                if (created) {
                    log.info(
                            "[RAFFLE_TICKET_CREATED] Raffle ticket created - RaffleId: {}, UserId: {}, TicketNumber: {}, Count: {}/{}",
                            raffle.getRaffleId(), userId, ticketNumber, i + 1, quantity);
                } else {
                    log.error(
                            "[RAFFLE_TICKET_FAILED] Failed to create raffle ticket - RaffleId: {}, UserId: {}, TicketNumber: {}, Count: {}/{}",
                            raffle.getRaffleId(), userId, ticketNumber, i + 1, quantity);
                    throw new RuntimeException("Raffle 티켓 생성에 실패했습니다.");
                }
            }

            log.info(
                    "[TICKET_PRODUCT_PROCESS_SUCCESS] All tickets created - ProductId: {}, RaffleId: {}, UserId: {}, TotalCount: {}",
                    product.getId(), raffle.getRaffleId(), userId, quantity);
        }
    }
}
