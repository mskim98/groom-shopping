package groom.backend.application.order;

import groom.backend.application.coupon.CouponIssueService;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.interfaces.cart.persistence.CartItemJpaEntity;
import groom.backend.interfaces.cart.persistence.SpringDataCartItemRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final SpringDataCartItemRepository cartItemRepository;
    private final SpringDataProductRepository productRepository;
    private final CouponIssueService couponIssueService;
    private final PaymentRepository paymentRepository;

    @Transactional
    public Order createOrder(Long userId, Long couponId) {

        // 사용자 장바구니의 상품 정보 조회하여 (productId, quantity) 리스트로 받기
        List<CartItemJpaEntity> cartItemProducts = cartItemRepository.findByUserId(userId);

        if (cartItemProducts.isEmpty()) {
            throw new IllegalArgumentException("장바구니가 비어있습니다.");
        }

        // productId(UUID) 만 리스트로 추출
        List<UUID> productIds = cartItemProducts.stream()
                .map(CartItemJpaEntity::getProductId)
                .toList();

        // productIds(UUID 리스트)로 상품 정보 조회 (이름, 가격 등)
        List<ProductJpaEntity> products = productRepository.findByIdIn(productIds);

        if (products.size() != productIds.size()) {
            throw new IllegalArgumentException("일부 상품 정보를 찾을 수 없습니다.");
        }

        Map<UUID, ProductJpaEntity> productMap = products.stream()
                .collect(Collectors.toMap(ProductJpaEntity::getId, product -> product));

        // 기본 주문 발행
        Order order = Order.builder()
                .userId(userId)
                .couponId(couponId)
                .build();

        // 각 장바구니 아이템을 OrderItem으로 변환하여 추가
        for (CartItemJpaEntity cartItem : cartItemProducts) {
            UUID productId = cartItem.getProductId();
            Integer quantity = cartItem.getQuantity();

            // 상품 조회
            ProductJpaEntity product = productMap.get(productId);

            if (product == null) {
                throw new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId);
            }

            // 상품 상태 확인
            if (!product.getIsActive()) {
                throw new IllegalArgumentException(
                        String.format("상품을 구매할 수 없습니다: %s", product.getName())
                );
            }

            // 재고 확인
            if (product.getStock() < quantity) {
                throw new IllegalArgumentException(
                        String.format("재고가 부족합니다. 상품: %s, 요청: %d, 재고: %d",
                                product.getName(),
                                quantity,
                                product.getStock())
                );
            }

            // OrderItem 생성 (주문 시점의 상품 정보 스냅샷)
            OrderItem orderItem = OrderItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .price(product.getPrice())
                    .quantity(quantity)
                    .build();

            // Order에 OrderItem 추가
            order.addOrderItem(orderItem);

            log.info("OrderItem added - Product: {}, Quantity: {}, Price: {}, Subtotal: {}",
                    orderItem.getProductName(),
                    orderItem.getQuantity(),
                    orderItem.getPrice(),
                    orderItem.getSubtotal());
        }

        order.calculateAmounts();

        // 쿠폰 할인 적용 (쿠폰이 있는 경우)
        if (couponId != null) {
            Integer discountAmount = couponIssueService.calculateDiscount(couponId, userId, order.getSubTotal());
            order.setDiscountAmount(discountAmount);
            log.info("Coupon applied - couponId: {}, discountAmount: {}", couponId, discountAmount);
        }

        // 최종 금액 계산 (subtotal, discount, total)
        order.calculateAmounts();

        // Order 저장 (cascade로 OrderItem 저장)
        Order savedOrder = orderRepository.save(order);

        // Payment 자동 생성 (PENDING 상태)
        String orderName = createOrderName(savedOrder.getOrderItems());
        Payment payment = Payment.builder()
                .order(savedOrder)
                .userId(userId)
                .amount(savedOrder.getTotalAmount())
                .orderName(orderName)
                .method(PaymentMethod.CARD) // 기본값: 카드 결제
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        // Order에 Payment 연결
        savedOrder.assignPayment(savedPayment);
        orderRepository.save(savedOrder);

        log.info("[PAYMENT_AUTO_CREATED] Payment automatically created - PaymentId: {}, OrderId: {}, Amount: {}",
                savedPayment.getId(), savedOrder.getId(), savedPayment.getAmountValue());

        return savedOrder;
    }

    // 주문 상세 조회
    public Order getOrderById(UUID orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        // 본인의 주문만 조회 가능
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("조회 권한이 없습니다.");
        }

        return order;
    }

    // 주문명 생성 헬퍼 메서드
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
}