package groom.backend.application.order;

import groom.backend.application.coupon.CouponIssueService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
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


// @Slf4j : log 객체 생성.
@Slf4j
// @Service : 주문 유스케이스를 조립하는 응용 서비스 빈.
@Service
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
// @Transactional(readOnly = true) : 기본 읽기 전용. 쓰기 메서드(createOrder)에만 @Transactional 추가.
@Transactional(readOnly = true)
public class OrderApplicationService {

    // private final : 주문 생성에 필요한 협력 객체들. 스프링이 주입하고 교체 불가 → 안전하게 사용.
    private final OrderRepository orderRepository;
    private final SpringDataCartItemRepository cartItemRepository;
    private final SpringDataProductRepository productRepository;
    private final CouponIssueService couponIssueService; // 쿠폰 할인액 계산 협력
    private final PaymentRepository paymentRepository;   // 주문과 함께 결제(PENDING) 자동 생성

    // 주문 생성 데이터 흐름:
    // 1) 장바구니 조회 → 2) 상품 검증(존재/판매중/재고) → 3) OrderItem 스냅샷 생성
    // 4) 금액 계산 → 5) 쿠폰 할인 적용 → 6) 주문 저장 → 7) 결제(PENDING) 자동 생성
    // @Transactional : 위 단계 중 하나라도 실패하면 전부 롤백되어 어중간한 주문이 남지 않게 한다.
    @Transactional
    public Order createOrder(Long userId, Long couponId) {

        // 사용자 장바구니의 상품 정보 조회하여 (productId, quantity) 리스트로 받기
        List<CartItemJpaEntity> cartItemProducts = cartItemRepository.findByUserId(userId);

        if (cartItemProducts.isEmpty()) {
            // 빈 카트 주문은 미처리 예외(500)가 아니라 비즈니스 예외(400)로 처리한다.
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        // productId(UUID) 만 리스트로 추출
        List<UUID> productIds = cartItemProducts.stream()
                .map(CartItemJpaEntity::getProductId)
                .toList();

        // productIds(UUID 리스트)로 상품 정보 조회 (이름, 가격 등)
        List<ProductJpaEntity> products = productRepository.findByIdIn(productIds);

        if (products.size() != productIds.size()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
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
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }

            // 상품 상태 확인
            if (!product.getIsActive()) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_ACTIVE);
            }

            // 재고 확인
            if (product.getStock() < quantity) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
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
            System.out.println("discountAmount : " + discountAmount);
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
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        // 본인의 주문만 조회 가능
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
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