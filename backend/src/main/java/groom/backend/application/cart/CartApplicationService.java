package groom.backend.application.cart;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.cart.persistence.CartItemJpaEntity;
import groom.backend.interfaces.cart.persistence.CartJpaEntity;
import groom.backend.interfaces.cart.persistence.SpringDataCartItemRepository;
import groom.backend.interfaces.cart.persistence.SpringDataCartRepository;
import groom.backend.infrastructure.resilience.TransactionBulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 장바구니 관련 비즈니스 로직을 처리하는 Application Service입니다.
 * 장바구니 저장소는 DB(cart · cart_item) 하나이며 조회와 변경 모두 DB 만 거친다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartApplicationService {

    private final SpringDataCartRepository cartRepository;
    private final SpringDataCartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final SpringDataUserRepository userJpaRepository;
    private final OrderRepository orderRepository; // 주문한 항목을 장바구니에서 뺄 때 주문을 읽는다
    private final JdbcTemplate jdbc; // 주문별 한 번 표시를 INSERT ... ON CONFLICT 로 차지한다

    /**
     * 장바구니 추가 결과를 담는 내부 클래스
     */
    public static class CartAddResult {
        private final Long cartId;
        private final Long cartItemId;
        private final Integer quantity;

        public CartAddResult(Long cartId, Long cartItemId, Integer quantity) {
            this.cartId = cartId;
            this.cartItemId = cartItemId;
            this.quantity = quantity;
        }

        public Long getCartId() {
            return cartId;
        }

        public Long getCartItemId() {
            return cartItemId;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }

    /**
     * 장바구니에 제품을 추가합니다.
     * 이미 장바구니에 있는 경우 수량을 증가시킵니다.
     *
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @param quantity 수량
     * @return 저장된 장바구니 정보
     */
    @Transactional
    @TransactionBulkhead
    public CartAddResult addToCart(Long userId, UUID productId, Integer quantity) {
        log.info("[CART_ADD_START] userId={}, productId={}, quantity={}", userId, productId, quantity);

        // 1. 사용자 확인
        UserJpaEntity user = userJpaRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 2. 제품 확인
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (!product.getIsActive()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_ACTIVE);
        }

        // 3. 재고 확인
        if (product.getStock() < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, 
                    "재고가 부족합니다. 현재 재고: " + product.getStock());
        }

        // 4. 사용자의 장바구니 찾기 또는 생성
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    CartJpaEntity newCart = CartJpaEntity.builder()
                            .user(user)
                            .build();
                    return cartRepository.save(newCart);
                });

        // 5. 장바구니 항목 찾기 또는 생성
        CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElse(null);

        if (cartItem != null) {
            // 이미 장바구니에 있으면 수량 증가
            int oldQuantity = cartItem.getQuantity();
            int newQuantity = oldQuantity + quantity;

            // 재고 확인 (기존 수량 + 새로 추가할 수량)
            if (product.getStock() < newQuantity) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, 
                        "재고가 부족합니다. 현재 재고: " + product.getStock() + ", 장바구니 수량: " + oldQuantity);
            }

            cartItem.setQuantity(newQuantity);
            log.info("[CART_UPDATE_QUANTITY] userId={}, productId={}, oldQuantity={}, newQuantity={}",
                    userId, productId, oldQuantity, newQuantity);
        } else {
            // 새로 추가
            cartItem = CartItemJpaEntity.builder()
                    .cart(cart)
                    .productId(productId)
                    .quantity(quantity)
                    .build();
            cart.addCartItem(cartItem);
            log.info("[CART_NEW_ITEM] userId={}, productId={}, quantity={}", userId, productId, quantity);
        }

        // 6. DB 저장
        CartItemJpaEntity savedCartItem = cartItemRepository.save(cartItem);
        CartJpaEntity savedCart = cartRepository.save(cart);

        log.info("[CART_ADD_SUCCESS] cartId={}, cartItemId={}, userId={}, productId={}, quantity={}",
                savedCart.getId(), savedCartItem.getId(), userId, productId, savedCartItem.getQuantity());

        return new CartAddResult(savedCart.getId(), savedCartItem.getId(), savedCartItem.getQuantity());
    }

    /**
     * 사용자의 장바구니에 담긴 모든 제품을 DB 에서 조회합니다
     *
     * @param userId 사용자 ID
     * @return 장바구니 정보
     */
    @Transactional(readOnly = true)
    @TransactionBulkhead
    public CartViewResult getCartItems(Long userId) {
        log.info("[CART_VIEW_START] userId={}", userId);

        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElse(null);

        if (cart == null) {
            log.info("[CART_VIEW_EMPTY] userId={}", userId);
            return new CartViewResult(null, List.of(), 0, 0);
        }

        List<CartItemJpaEntity> cartItems = cartItemRepository.findByUserId(userId);

        if (cartItems.isEmpty()) {
            log.info("[CART_VIEW_NO_ITEMS] userId={}, cartId={}", userId, cart.getId());
            return new CartViewResult(cart.getId(), List.of(), 0, 0);
        }

        return buildCartViewResultFromDB(cart, cartItems);
    }

    /**
     * DB 데이터로부터 장바구니 조회 결과 생성
     */
    private CartViewResult buildCartViewResultFromDB(CartJpaEntity cart, List<CartItemJpaEntity> cartItems) {
        // 1. 제품 정보 조회
        List<UUID> productIds = cartItems.stream()
                .map(CartItemJpaEntity::getProductId)
                .toList();

        List<Product> products = productRepository.findByIds(productIds);
        
        // 2. 제품 정보를 Map으로 변환
        Map<UUID, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        // 3. 결과 생성
        int totalItems = cartItems.size();
        int totalPrice = 0;
        List<CartItemResult> itemResults = new java.util.ArrayList<>();

        for (CartItemJpaEntity cartItem : cartItems) {
            Product product = productMap.get(cartItem.getProductId());
            if (product != null) {
                int itemTotalPrice = product.getPrice() * cartItem.getQuantity();
                totalPrice += itemTotalPrice;

                itemResults.add(new CartItemResult(
                        cartItem.getId(),
                        cartItem.getProductId(),
                        product.getName(),
                        product.getPrice(),
                        cartItem.getQuantity(),
                        itemTotalPrice,
                        cartItem.getCreatedAt(),
                        cartItem.getUpdatedAt()
                ));
            }
        }

        log.info("[CART_VIEW_DB_SUCCESS] userId={}, cartId={}, totalItems={}, totalPrice={}", 
                cart.getUser().getId(), cart.getId(), totalItems, totalPrice);

        return new CartViewResult(cart.getId(), itemResults, totalItems, totalPrice);
    }

    /**
     * 장바구니 조회 결과를 담는 내부 클래스
     */
    public static class CartViewResult {
        private final Long cartId;
        private final List<CartItemResult> items;
        private final Integer totalItems;
        private final Integer totalPrice;

        public CartViewResult(Long cartId, List<CartItemResult> items, Integer totalItems, Integer totalPrice) {
            this.cartId = cartId;
            this.items = items;
            this.totalItems = totalItems;
            this.totalPrice = totalPrice;
        }

        public Long getCartId() {
            return cartId;
        }

        public List<CartItemResult> getItems() {
            return items;
        }

        public Integer getTotalItems() {
            return totalItems;
        }

        public Integer getTotalPrice() {
            return totalPrice;
        }
    }

    /**
     * 장바구니 항목 결과를 담는 내부 클래스
     */
    public static class CartItemResult {
        private final Long cartItemId;
        private final UUID productId;
        private final String productName;
        private final Integer price;
        private final Integer quantity;
        private final Integer totalPrice;
        private final java.time.LocalDateTime createdAt;
        private final java.time.LocalDateTime updatedAt;

        public CartItemResult(Long cartItemId, UUID productId, String productName, 
                             Integer price, Integer quantity, Integer totalPrice,
                             java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
            this.cartItemId = cartItemId;
            this.productId = productId;
            this.productName = productName;
            this.price = price;
            this.quantity = quantity;
            this.totalPrice = totalPrice;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public Long getCartItemId() { return cartItemId; }
        public UUID getProductId() { return productId; }
        public String getProductName() { return productName; }
        public Integer getPrice() { return price; }
        public Integer getQuantity() { return quantity; }
        public Integer getTotalPrice() { return totalPrice; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    }

    /**
     * 장바구니에서 제품 수량을 줄이거나 제거합니다.
     * 하나 또는 여러 개의 제품을 한 번에 처리할 수 있습니다.
     * 수량이 0이 되면 항목을 제거하고, 수량이 남으면 수량만 감소시킵니다.
     *
     * @param userId 사용자 ID
     * @param itemsToRemove 제거할 제품 목록 (productId, quantity)
     * @return 제거 결과 리스트
     */
    @Transactional
    @TransactionBulkhead
    public CartRemoveBatchResult removeCartItems(Long userId, List<CartItemToRemove> itemsToRemove) {
        log.info("[CART_REMOVE_BATCH_START] userId={}, itemCount={}", userId, itemsToRemove.size());

        // 1. 사용자의 장바구니 조회
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));

        // 2. 사전 검증: 모든 항목을 검증하고 하나라도 문제가 있으면 예외 발생
        List<CartItemJpaEntity> cartItemsToProcess = new java.util.ArrayList<>();
        ErrorCode validationErrorCode = null;
        String detailMessage = null;

        for (CartItemToRemove item : itemsToRemove) {
            UUID productId = item.getProductId();
            Integer quantity = item.getQuantity();

            // 수량 검증
            if (quantity == null || quantity < 1) {
                validationErrorCode = ErrorCode.INVALID_REMOVE_QUANTITY;
                break;
            }

            // 장바구니 항목 조회
            CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                    .orElse(null);

            if (cartItem == null) {
                validationErrorCode = ErrorCode.CART_ITEM_NOT_FOUND;
                detailMessage = "제품 ID: " + productId;
                break;
            }

            // 현재 수량 확인
            int currentQuantity = cartItem.getQuantity();
            log.info("[CART_REMOVE_VALIDATE] userId={}, productId={}, currentQuantity={}, removeQuantity={}", 
                    userId, productId, currentQuantity, quantity);

            // 수량 검증 (장바구니에 있는 수량보다 많이 제거할 수 없음)
            if (quantity > currentQuantity) {
                validationErrorCode = ErrorCode.CART_QUANTITY_EXCEEDED;
                detailMessage = "장바구니 수량: " + currentQuantity + ", 요청 수량: " + quantity;
                break;
            }

            // 검증 통과한 항목만 추가
            cartItemsToProcess.add(cartItem);
        }

        // 3. 하나라도 검증 실패하면 전체 실패 (트랜잭션 롤백)
        if (validationErrorCode != null) {
            log.error("[CART_REMOVE_VALIDATION_FAILED] userId={}, errorCode={}, detail={}", 
                    userId, validationErrorCode, detailMessage);
            throw new BusinessException(validationErrorCode, detailMessage);
        }

        // 4. 모든 검증 통과 - 실제 제거 처리
        List<CartRemoveResult> results = new java.util.ArrayList<>();

        for (int i = 0; i < itemsToRemove.size(); i++) {
            CartItemToRemove item = itemsToRemove.get(i);
            CartItemJpaEntity cartItem = cartItemsToProcess.get(i);
            UUID productId = item.getProductId();
            Integer quantity = item.getQuantity();
            int currentQuantity = cartItem.getQuantity();
            int remainingQuantity = currentQuantity - quantity;

            if (remainingQuantity == 0) {
                // 수량이 0이면 항목 제거
                cart.removeCartItem(cartItem);
                cartItemRepository.delete(cartItem);

                log.info("[CART_REMOVE_COMPLETE] userId={}, productId={}, cartItemId={}, removed=true", 
                        userId, productId, cartItem.getId());
            } else {
                // 수량이 남으면 수량만 감소
                cartItem.setQuantity(remainingQuantity);
                cartItemRepository.save(cartItem);

                log.info("[CART_REMOVE_PARTIAL] userId={}, productId={}, cartItemId={}, remainingQuantity={}", 
                        userId, productId, cartItem.getId(), remainingQuantity);
            }

            results.add(new CartRemoveResult(productId, quantity, remainingQuantity, remainingQuantity == 0));
        }

        // 5. 장바구니 저장
        cartRepository.save(cart);

        log.info("[CART_REMOVE_BATCH_SUCCESS] userId={}, totalItems={}, successCount={}", 
                userId, itemsToRemove.size(), results.size());

        return new CartRemoveBatchResult(results, results.size(), 0);
    }

    /**
     * 확정된 주문의 항목을 주문 주인의 장바구니에서 뺀다. 주문 하나당 한 번만 빼고, 이미 뺀 주문의 재호출은 아무것도 하지 않고 돌아간다
     * 남의 주문은 ACCESS_DENIED(주문 상세 조회와 같은 확인), 확정되지 않은 주문은 ORDER_NOT_CONFIRMED 로 거절하며 거절은 표시를 남기지 않는다
     * 표시는 같은 트랜잭션에서 차지한다 - 동시에 온 두 호출 중 늦은 쪽은 먼저 온 쪽이 커밋할 때까지 INSERT 에서 기다렸다가 0건으로 돌아간다
     */
    @Transactional
    public void clearOrderedItemsOf(Long userId, UUID orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CONFIRMED);
        }
        int claimed = jdbc.update(
                "INSERT INTO cart_ordered_items_cleared (order_id) VALUES (?) ON CONFLICT (order_id) DO NOTHING", orderId);
        if (claimed == 0) {
            log.info("[CART_CLEAR_ORDERED_ALREADY] userId={}, orderId={}", userId, orderId);
            return;
        }
        clearOrderedItems(userId, order.getOrderItems().stream()
                .map(item -> new CartItemToRemove(item.getProductId(), item.getQuantity()))
                .toList());
    }

    /**
     * 결제 흐름에서는 부르지 않는다 - 클라이언트가 결제 뒤 부르는 DELETE /v1/cart/ordered-items/{orderId} 가 주문당 한 번 부른다
     * 주문한 수량을 장바구니에서 뺀다 - 호출한 트랜잭션에 합류하므로 그 트랜잭션이 롤백되면 이 변경도 되돌아온다
     * {@link #removeCartItems} 와 달리 던지지 않는다 - 장바구니나 줄이 이미 없거나(다른 탭에서 지움 · 같은 장바구니로 만든 먼저 승인된 주문이 비움)
     * 주문 수량이 줄의 수량보다 크면, 던지는 순간 이미 승인된 결제가 롤백되고 보상(환불)으로 이어지기 때문이다
     * 없는 줄은 건너뛰고 수량은 줄의 수량까지만 빼며 수량이 0 이 된 줄은 지운다
     *
     * @param userId 주문한 사용자 ID
     * @param ordered 주문 항목(productId, quantity)
     */
    @Transactional
    public void clearOrderedItems(Long userId, List<CartItemToRemove> ordered) {
        // 같은 사용자의 승인 둘이 한 줄의 수량을 동시에 읽고 각자 빼면 한 번만 빠진다 - 장바구니 행 잠금으로 순서를 세운다
        CartJpaEntity cart = cartRepository.findByUserIdForUpdate(userId).orElse(null);
        if (cart == null) {
            log.info("[CART_CLEAR_ORDERED_NO_CART] userId={}", userId);
            return;
        }

        for (CartItemToRemove item : ordered) {
            CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), item.getProductId())
                    .orElse(null);
            if (cartItem == null) {
                log.info("[CART_CLEAR_ORDERED_NO_ITEM] userId={}, productId={}", userId, item.getProductId());
                continue;
            }
            int remaining = cartItem.getQuantity() - item.getQuantity();
            if (remaining <= 0) {
                cart.removeCartItem(cartItem);
                cartItemRepository.delete(cartItem);
            } else {
                cartItem.setQuantity(remaining);
                cartItemRepository.save(cartItem);
            }
        }
        cartRepository.save(cart);

        log.info("[CART_CLEAR_ORDERED_SUCCESS] userId={}, itemCount={}", userId, ordered.size());
    }

    /**
     * 장바구니 제품 수량을 증가시킵니다.
     * 재고량을 확인하여 재고량을 초과하지 않도록 합니다.
     *
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @return 수량 변경 결과
     */
    @Transactional
    @TransactionBulkhead
    public CartQuantityUpdateResult increaseQuantity(Long userId, UUID productId) {
        log.info("[CART_INCREASE_QUANTITY_START] userId={}, productId={}", userId, productId);

        // 1. 사용자의 장바구니 조회
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));

        // 2. 장바구니 항목 조회
        CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));

        // 3. 제품 정보 조회 및 재고 확인
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (!product.getIsActive()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_ACTIVE);
        }

        // 4. 현재 수량 및 재고 확인
        int currentQuantity = cartItem.getQuantity();
        int productStock = product.getStock();
        int newQuantity = currentQuantity + 1;

        // 5. 재고 확인 (장바구니 수량이 재고량을 초과할 수 없음)
        if (newQuantity > productStock) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    String.format("재고가 부족합니다. 현재 재고: %d개, 장바구니 수량: %d개", 
                            productStock, currentQuantity));
        }

        // 6. 수량 증가
        cartItem.setQuantity(newQuantity);
        cartItemRepository.save(cartItem);

        log.info("[CART_INCREASE_QUANTITY_SUCCESS] userId={}, productId={}, oldQuantity={}, newQuantity={}, stock={}",
                userId, productId, currentQuantity, newQuantity, productStock);

        return new CartQuantityUpdateResult(productId, newQuantity, productStock);
    }

    /**
     * 장바구니 제품 수량을 감소시킵니다.
     * 수량이 1이면 감소하지 않고, 2 이상이면 1개 감소시킵니다.
     * 수량이 0이 되면 항목을 제거합니다.
     *
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @return 수량 변경 결과 (isRemoved: true면 제거됨)
     */
    @Transactional
    @TransactionBulkhead
    public CartQuantityUpdateResult decreaseQuantity(Long userId, UUID productId) {
        log.info("[CART_DECREASE_QUANTITY_START] userId={}, productId={}", userId, productId);

        // 1. 사용자의 장바구니 조회
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));

        // 2. 장바구니 항목 조회
        CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));

        // 3. 제품 정보 조회
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        // 4. 현재 수량 확인
        int currentQuantity = cartItem.getQuantity();

        // 5. 수량이 1이면 감소하지 않음
        if (currentQuantity <= 1) {
            log.info("[CART_DECREASE_QUANTITY_MIN] userId={}, productId={}, quantity={}, cannot decrease below 1",
                    userId, productId, currentQuantity);
            throw new BusinessException(ErrorCode.CART_QUANTITY_MINIMUM, 
                    "수량은 1개 이상이어야 합니다. 삭제하려면 삭제 버튼을 사용하세요.");
        }

        // 6. 수량 감소
        int newQuantity = currentQuantity - 1;
        cartItem.setQuantity(newQuantity);
        cartItemRepository.save(cartItem);

        log.info("[CART_DECREASE_QUANTITY_SUCCESS] userId={}, productId={}, oldQuantity={}, newQuantity={}, stock={}",
                userId, productId, currentQuantity, newQuantity, product.getStock());

        return new CartQuantityUpdateResult(productId, newQuantity, product.getStock(), false);
    }

    /**
     * 수량 변경 결과를 담는 내부 클래스
     */
    public static class CartQuantityUpdateResult {
        private final UUID productId;
        private final Integer quantity;
        private final Integer stock;
        private final Boolean isRemoved;

        public CartQuantityUpdateResult(UUID productId, Integer quantity, Integer stock) {
            this.productId = productId;
            this.quantity = quantity;
            this.stock = stock;
            this.isRemoved = false;
        }

        public CartQuantityUpdateResult(UUID productId, Integer quantity, Integer stock, Boolean isRemoved) {
            this.productId = productId;
            this.quantity = quantity;
            this.stock = stock;
            this.isRemoved = isRemoved;
        }

        public UUID getProductId() { return productId; }
        public Integer getQuantity() { return quantity; }
        public Integer getStock() { return stock; }
        public Boolean getIsRemoved() { return isRemoved; }
    }

    /**
     * 제거할 제품 정보를 담는 내부 클래스
     */
    public static class CartItemToRemove {
        private final UUID productId;
        private final Integer quantity;

        public CartItemToRemove(UUID productId, Integer quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public UUID getProductId() { return productId; }
        public Integer getQuantity() { return quantity; }
    }

    /**
     * 장바구니 제거 결과를 담는 내부 클래스
     */
    public static class CartRemoveResult {
        private final UUID productId;
        private final Integer removedQuantity;
        private final Integer remainingQuantity;
        private final Boolean isRemoved;

        public CartRemoveResult(UUID productId, Integer removedQuantity, Integer remainingQuantity, Boolean isRemoved) {
            this.productId = productId;
            this.removedQuantity = removedQuantity;
            this.remainingQuantity = remainingQuantity;
            this.isRemoved = isRemoved;
        }

        public UUID getProductId() { return productId; }
        public Integer getRemovedQuantity() { return removedQuantity; }
        public Integer getRemainingQuantity() { return remainingQuantity; }
        public Boolean getIsRemoved() { return isRemoved; }
    }

    /**
     * 장바구니 일괄 제거 결과를 담는 내부 클래스
     */
    public static class CartRemoveBatchResult {
        private final List<CartRemoveResult> results;
        private final Integer successCount;
        private final Integer failCount;

        public CartRemoveBatchResult(List<CartRemoveResult> results, Integer successCount, Integer failCount) {
            this.results = results;
            this.successCount = successCount;
            this.failCount = failCount;
        }

        public List<CartRemoveResult> getResults() { return results; }
        public Integer getSuccessCount() { return successCount; }
        public Integer getFailCount() { return failCount; }
    }
}

