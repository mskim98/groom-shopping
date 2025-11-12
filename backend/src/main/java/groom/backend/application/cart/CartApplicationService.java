package groom.backend.application.cart;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.infrastructure.cart.RedisCartRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.cart.persistence.CartItemJpaEntity;
import groom.backend.interfaces.cart.persistence.CartJpaEntity;
import groom.backend.interfaces.cart.persistence.SpringDataCartItemRepository;
import groom.backend.interfaces.cart.persistence.SpringDataCartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 장바구니 관련 비즈니스 로직을 처리하는 Application Service입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartApplicationService {

    private final SpringDataCartRepository cartRepository;
    private final SpringDataCartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final SpringDataUserRepository userJpaRepository;
    private final RedisCartRepository redisCartRepository;

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
    public CartAddResult addToCart(Long userId, UUID productId, Integer quantity) {
        log.info("[CART_ADD_START] userId={}, productId={}, quantity={}", userId, productId, quantity);

        // 1. 사용자 확인
        UserJpaEntity user = userJpaRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 2. 제품 확인
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다."));

        if (!product.getIsActive()) {
            throw new IllegalArgumentException("판매 중지된 제품입니다.");
        }

        // 3. 재고 확인
        if (product.getStock() < quantity) {
            throw new IllegalArgumentException("재고가 부족합니다. 현재 재고: " + product.getStock());
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
                throw new IllegalArgumentException("재고가 부족합니다. 현재 재고: " + product.getStock() + ", 장바구니 수량: " + oldQuantity);
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

        // 7. Redis에 동시 저장 (Write-Through 패턴)
        try {
            redisCartRepository.addOrUpdateItem(userId, productId, savedCartItem.getQuantity(), savedCartItem.getId());
            log.debug("[CART_REDIS_SAVED] userId={}, productId={}, quantity={}", userId, productId, savedCartItem.getQuantity());
        } catch (Exception e) {
            // Redis 저장 실패해도 DB는 저장되었으므로 로그만 남김
            log.warn("[CART_REDIS_SAVE_FAILED] userId={}, productId={}, error={}", userId, productId, e.getMessage());
        }

        log.info("[CART_ADD_SUCCESS] cartId={}, cartItemId={}, userId={}, productId={}, quantity={}",
                savedCart.getId(), savedCartItem.getId(), userId, productId, savedCartItem.getQuantity());

        return new CartAddResult(savedCart.getId(), savedCartItem.getId(), savedCartItem.getQuantity());
    }

    /**
     * 사용자의 장바구니에 담긴 모든 제품을 조회합니다.
     * Read-Through 패턴: Redis 우선 조회, 없으면 DB에서 조회 후 Redis에 캐싱
     *
     * @param userId 사용자 ID
     * @return 장바구니 정보
     */
    @Transactional(readOnly = true)
    public CartViewResult getCartItems(Long userId) {
        log.info("[CART_VIEW_START] userId={}", userId);

        // 1. Redis에서 먼저 조회 시도 (Read-Through 패턴)
        Map<UUID, RedisCartRepository.CartItemData> redisItems = redisCartRepository.getAllItems(userId);
        
        if (!redisItems.isEmpty()) {
            log.debug("[CART_VIEW_REDIS_HIT] userId={}, itemCount={}", userId, redisItems.size());
            return buildCartViewResultFromRedis(userId, redisItems);
        }

        // 2. Redis에 없으면 DB에서 조회
        log.debug("[CART_VIEW_REDIS_MISS] userId={}, loading from DB", userId);
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElse(null);

        if (cart == null) {
            log.info("[CART_VIEW_EMPTY] userId={}", userId);
            return new CartViewResult(null, List.of(), 0, 0);
        }

        // 3. 장바구니 항목 조회
        List<CartItemJpaEntity> cartItems = cartItemRepository.findByUserId(userId);

        if (cartItems.isEmpty()) {
            log.info("[CART_VIEW_NO_ITEMS] userId={}, cartId={}", userId, cart.getId());
            return new CartViewResult(cart.getId(), List.of(), 0, 0);
        }

        // 4. DB 조회 결과를 Redis에 캐싱
        try {
            for (CartItemJpaEntity cartItem : cartItems) {
                redisCartRepository.addOrUpdateItem(
                        userId, 
                        cartItem.getProductId(), 
                        cartItem.getQuantity(), 
                        cartItem.getId()
                );
            }
            log.debug("[CART_VIEW_REDIS_CACHED] userId={}, itemCount={}", userId, cartItems.size());
        } catch (Exception e) {
            log.warn("[CART_VIEW_REDIS_CACHE_FAILED] userId={}, error={}", userId, e.getMessage());
        }

        // 5. 제품 정보 조회 및 결과 생성
        return buildCartViewResultFromDB(cart, cartItems);
    }

    /**
     * Redis 데이터로부터 장바구니 조회 결과 생성
     */
    private CartViewResult buildCartViewResultFromRedis(Long userId, Map<UUID, RedisCartRepository.CartItemData> redisItems) {
        // 1. 제품 정보 조회
        List<UUID> productIds = new java.util.ArrayList<>(redisItems.keySet());
        List<Product> products = productRepository.findByIds(productIds);
        
        // 2. 제품 정보를 Map으로 변환
        Map<UUID, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        // 3. 결과 생성
        int totalItems = redisItems.size();
        int totalPrice = 0;
        List<CartItemResult> itemResults = new java.util.ArrayList<>();

        for (Map.Entry<UUID, RedisCartRepository.CartItemData> entry : redisItems.entrySet()) {
            UUID productId = entry.getKey();
            RedisCartRepository.CartItemData itemData = entry.getValue();
            Product product = productMap.get(productId);
            
            if (product != null) {
                int itemTotalPrice = product.getPrice() * itemData.getQuantity();
                totalPrice += itemTotalPrice;

                itemResults.add(new CartItemResult(
                        itemData.getCartItemId(),
                        productId,
                        product.getName(),
                        product.getPrice(),
                        itemData.getQuantity(),
                        itemTotalPrice,
                        null, // Redis에는 timestamp 없음
                        null
                ));
            }
        }

        log.info("[CART_VIEW_REDIS_SUCCESS] userId={}, totalItems={}, totalPrice={}", 
                userId, totalItems, totalPrice);

        return new CartViewResult(null, itemResults, totalItems, totalPrice);
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
    public CartRemoveBatchResult removeCartItems(Long userId, List<CartItemToRemove> itemsToRemove) {
        log.info("[CART_REMOVE_BATCH_START] userId={}, itemCount={}", userId, itemsToRemove.size());

        // 1. 사용자의 장바구니 조회
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("장바구니가 존재하지 않습니다."));

        // 2. 사전 검증: 모든 항목을 검증하고 하나라도 문제가 있으면 예외 발생
        List<CartItemJpaEntity> cartItemsToProcess = new java.util.ArrayList<>();
        String validationError = null;

        for (CartItemToRemove item : itemsToRemove) {
            UUID productId = item.getProductId();
            Integer quantity = item.getQuantity();

            // 수량 검증
            if (quantity == null || quantity < 1) {
                validationError = "제거할 수량은 1 이상이어야 합니다.";
                break;
            }

            // 장바구니 항목 조회
            CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                    .orElse(null);

            if (cartItem == null) {
                validationError = "해당 제품이 장바구니에 존재하지 않습니다.";
                break;
            }

            // 현재 수량 확인
            int currentQuantity = cartItem.getQuantity();
            log.info("[CART_REMOVE_VALIDATE] userId={}, productId={}, currentQuantity={}, removeQuantity={}", 
                    userId, productId, currentQuantity, quantity);

            // 수량 검증 (장바구니에 있는 수량보다 많이 제거할 수 없음)
            if (quantity > currentQuantity) {
                validationError = "장바구니의 제품보다 수량이 큽니다.";
                break;
            }

            // 검증 통과한 항목만 추가
            cartItemsToProcess.add(cartItem);
        }

        // 3. 하나라도 검증 실패하면 전체 실패 (트랜잭션 롤백)
        if (validationError != null) {
            log.error("[CART_REMOVE_VALIDATION_FAILED] userId={}, error={}", userId, validationError);
            throw new IllegalArgumentException(validationError);
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
                
                // Redis에서도 제거
                try {
                    redisCartRepository.removeItem(userId, productId);
                } catch (Exception e) {
                    log.warn("[CART_REDIS_REMOVE_FAILED] userId={}, productId={}, error={}", 
                            userId, productId, e.getMessage());
                }
                
                log.info("[CART_REMOVE_COMPLETE] userId={}, productId={}, cartItemId={}, removed=true", 
                        userId, productId, cartItem.getId());
            } else {
                // 수량이 남으면 수량만 감소
                cartItem.setQuantity(remainingQuantity);
                cartItemRepository.save(cartItem);
                
                // Redis에서도 수량 업데이트
                try {
                    redisCartRepository.updateQuantity(userId, productId, remainingQuantity);
                } catch (Exception e) {
                    log.warn("[CART_REDIS_UPDATE_FAILED] userId={}, productId={}, error={}", 
                            userId, productId, e.getMessage());
                }
                
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
     * 장바구니 제품 수량을 증가시킵니다.
     * 재고량을 확인하여 재고량을 초과하지 않도록 합니다.
     *
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @return 수량 변경 결과
     */
    @Transactional
    public CartQuantityUpdateResult increaseQuantity(Long userId, UUID productId) {
        log.info("[CART_INCREASE_QUANTITY_START] userId={}, productId={}", userId, productId);

        // 1. 사용자의 장바구니 조회
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("장바구니가 존재하지 않습니다."));

        // 2. 장바구니 항목 조회
        CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new IllegalArgumentException("해당 제품이 장바구니에 존재하지 않습니다."));

        // 3. 제품 정보 조회 및 재고 확인
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다."));

        if (!product.getIsActive()) {
            throw new IllegalArgumentException("판매 중지된 제품입니다.");
        }

        // 4. 현재 수량 및 재고 확인
        int currentQuantity = cartItem.getQuantity();
        int productStock = product.getStock();
        int newQuantity = currentQuantity + 1;

        // 5. 재고 확인 (장바구니 수량이 재고량을 초과할 수 없음)
        if (newQuantity > productStock) {
            throw new IllegalArgumentException(
                    String.format("재고가 부족합니다. 현재 재고: %d개, 장바구니 수량: %d개", 
                            productStock, currentQuantity));
        }

        // 6. 수량 증가
        cartItem.setQuantity(newQuantity);
        cartItemRepository.save(cartItem);

        // 7. Redis에서도 수량 업데이트
        try {
            redisCartRepository.updateQuantity(userId, productId, newQuantity);
        } catch (Exception e) {
            log.warn("[CART_REDIS_UPDATE_QUANTITY_FAILED] userId={}, productId={}, error={}", 
                    userId, productId, e.getMessage());
        }

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
    public CartQuantityUpdateResult decreaseQuantity(Long userId, UUID productId) {
        log.info("[CART_DECREASE_QUANTITY_START] userId={}, productId={}", userId, productId);

        // 1. 사용자의 장바구니 조회
        CartJpaEntity cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("장바구니가 존재하지 않습니다."));

        // 2. 장바구니 항목 조회
        CartItemJpaEntity cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new IllegalArgumentException("해당 제품이 장바구니에 존재하지 않습니다."));

        // 3. 제품 정보 조회
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다."));

        // 4. 현재 수량 확인
        int currentQuantity = cartItem.getQuantity();

        // 5. 수량이 1이면 감소하지 않음
        if (currentQuantity <= 1) {
            log.info("[CART_DECREASE_QUANTITY_MIN] userId={}, productId={}, quantity={}, cannot decrease below 1",
                    userId, productId, currentQuantity);
            throw new IllegalArgumentException("수량은 1개 이상이어야 합니다. 삭제하려면 삭제 버튼을 사용하세요.");
        }

        // 6. 수량 감소
        int newQuantity = currentQuantity - 1;
        cartItem.setQuantity(newQuantity);
        cartItemRepository.save(cartItem);

        // 7. Redis에서도 수량 업데이트
        try {
            redisCartRepository.updateQuantity(userId, productId, newQuantity);
        } catch (Exception e) {
            log.warn("[CART_REDIS_UPDATE_QUANTITY_FAILED] userId={}, productId={}, error={}", 
                    userId, productId, e.getMessage());
        }

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

