package groom.backend.infrastructure.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis를 활용한 장바구니 저장소
 * - 빠른 읽기/쓰기 성능
 * - TTL 설정으로 임시 데이터 관리
 * - Hash 구조로 사용자별 장바구니 항목 저장
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisCartRepository {

    private static final String CART_KEY_PREFIX = "cart:";
    private static final long CART_TTL_DAYS = 7; // 7일 후 자동 삭제
    private static final long CART_TTL_SECONDS = TimeUnit.DAYS.toSeconds(CART_TTL_DAYS);

    @Qualifier("cartRedisTemplate")
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 장바구니 항목 정보를 담는 내부 클래스
     */
    public static class CartItemData {
        private UUID productId;
        private Integer quantity;
        private Long cartItemId; // DB의 cart_item_id (선택적)

        public CartItemData() {}

        public CartItemData(UUID productId, Integer quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public CartItemData(UUID productId, Integer quantity, Long cartItemId) {
            this.productId = productId;
            this.quantity = quantity;
            this.cartItemId = cartItemId;
        }

        // Getters and Setters
        public UUID getProductId() { return productId; }
        public void setProductId(UUID productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public Long getCartItemId() { return cartItemId; }
        public void setCartItemId(Long cartItemId) { this.cartItemId = cartItemId; }
    }

    /**
     * 사용자의 장바구니에 제품 추가 또는 수량 업데이트
     */
    public void addOrUpdateItem(Long userId, UUID productId, Integer quantity, Long cartItemId) {
        String cartKey = getCartKey(userId);
        String productIdStr = productId.toString();

        try {
            CartItemData itemData = new CartItemData(productId, quantity, cartItemId);
            String itemJson = objectMapper.writeValueAsString(itemData);

            redisTemplate.opsForHash().put(cartKey, productIdStr, itemJson);
            redisTemplate.expire(cartKey, CART_TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("[REDIS_CART_ADD] userId={}, productId={}, quantity={}, cartItemId={}", 
                    userId, productId, quantity, cartItemId);
        } catch (Exception e) {
            log.error("[REDIS_CART_ADD_FAILED] userId={}, productId={}, error={}", 
                    userId, productId, e.getMessage(), e);
            throw new RuntimeException("Redis 장바구니 저장 실패", e);
        }
    }

    /**
     * 사용자의 장바구니에서 제품 제거
     */
    public void removeItem(Long userId, UUID productId) {
        String cartKey = getCartKey(userId);
        String productIdStr = productId.toString();

        try {
            redisTemplate.opsForHash().delete(cartKey, productIdStr);
            log.debug("[REDIS_CART_REMOVE] userId={}, productId={}", userId, productId);
        } catch (Exception e) {
            log.error("[REDIS_CART_REMOVE_FAILED] userId={}, productId={}, error={}", 
                    userId, productId, e.getMessage(), e);
        }
    }

    /**
     * 사용자의 장바구니에서 여러 제품 제거
     */
    public void removeItems(Long userId, List<UUID> productIds) {
        String cartKey = getCartKey(userId);
        Object[] productIdStrs = productIds.stream()
                .map(UUID::toString)
                .toArray();

        try {
            redisTemplate.opsForHash().delete(cartKey, productIdStrs);
            log.debug("[REDIS_CART_REMOVE_BATCH] userId={}, productIds={}", userId, productIds);
        } catch (Exception e) {
            log.error("[REDIS_CART_REMOVE_BATCH_FAILED] userId={}, error={}", 
                    userId, e.getMessage(), e);
        }
    }

    /**
     * 사용자의 장바구니에서 제품 수량 업데이트
     */
    public void updateQuantity(Long userId, UUID productId, Integer quantity) {
        String cartKey = getCartKey(userId);
        String productIdStr = productId.toString();

        try {
            String existingItemJson = (String) redisTemplate.opsForHash().get(cartKey, productIdStr);
            
            if (existingItemJson != null) {
                CartItemData itemData = objectMapper.readValue(existingItemJson, CartItemData.class);
                itemData.setQuantity(quantity);
                String updatedItemJson = objectMapper.writeValueAsString(itemData);
                
                redisTemplate.opsForHash().put(cartKey, productIdStr, updatedItemJson);
                redisTemplate.expire(cartKey, CART_TTL_SECONDS, TimeUnit.SECONDS);
                
                log.debug("[REDIS_CART_UPDATE_QUANTITY] userId={}, productId={}, quantity={}", 
                        userId, productId, quantity);
            }
        } catch (Exception e) {
            log.error("[REDIS_CART_UPDATE_QUANTITY_FAILED] userId={}, productId={}, error={}", 
                    userId, productId, e.getMessage(), e);
        }
    }

    /**
     * 사용자의 모든 장바구니 항목 조회
     */
    public Map<UUID, CartItemData> getAllItems(Long userId) {
        String cartKey = getCartKey(userId);

        try {
            Map<Object, Object> hashMap = redisTemplate.opsForHash().entries(cartKey);
            
            if (hashMap.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<UUID, CartItemData> result = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hashMap.entrySet()) {
                String productIdStr = (String) entry.getKey();
                String itemJson = (String) entry.getValue();
                
                UUID productId = UUID.fromString(productIdStr);
                CartItemData itemData = objectMapper.readValue(itemJson, CartItemData.class);
                result.put(productId, itemData);
            }

            log.debug("[REDIS_CART_GET_ALL] userId={}, itemCount={}", userId, result.size());
            return result;
        } catch (Exception e) {
            log.error("[REDIS_CART_GET_ALL_FAILED] userId={}, error={}", 
                    userId, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 특정 제품의 장바구니 항목 조회
     */
    public Optional<CartItemData> getItem(Long userId, UUID productId) {
        String cartKey = getCartKey(userId);
        String productIdStr = productId.toString();

        try {
            String itemJson = (String) redisTemplate.opsForHash().get(cartKey, productIdStr);
            
            if (itemJson == null) {
                return Optional.empty();
            }

            CartItemData itemData = objectMapper.readValue(itemJson, CartItemData.class);
            return Optional.of(itemData);
        } catch (Exception e) {
            log.error("[REDIS_CART_GET_ITEM_FAILED] userId={}, productId={}, error={}", 
                    userId, productId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 사용자의 장바구니 전체 삭제
     */
    public void clearCart(Long userId) {
        String cartKey = getCartKey(userId);
        
        try {
            redisTemplate.delete(cartKey);
            log.debug("[REDIS_CART_CLEAR] userId={}", userId);
        } catch (Exception e) {
            log.error("[REDIS_CART_CLEAR_FAILED] userId={}, error={}", 
                    userId, e.getMessage(), e);
        }
    }

    /**
     * 사용자의 장바구니 존재 여부 확인
     */
    public boolean exists(Long userId) {
        String cartKey = getCartKey(userId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(cartKey));
    }

    /**
     * 사용자의 장바구니 항목 수 조회
     */
    public long getItemCount(Long userId) {
        String cartKey = getCartKey(userId);
        Long count = redisTemplate.opsForHash().size(cartKey);
        return count != null ? count : 0;
    }

    /**
     * Redis 키 생성
     */
    private String getCartKey(Long userId) {
        return CART_KEY_PREFIX + userId;
    }
}

