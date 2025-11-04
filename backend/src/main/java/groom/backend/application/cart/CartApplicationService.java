package groom.backend.application.cart;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
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

import java.util.UUID;

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

        // 6. 저장
        CartItemJpaEntity savedCartItem = cartItemRepository.save(cartItem);
        CartJpaEntity savedCart = cartRepository.save(cart);

        log.info("[CART_ADD_SUCCESS] cartId={}, cartItemId={}, userId={}, productId={}, quantity={}",
                savedCart.getId(), savedCartItem.getId(), userId, productId, savedCartItem.getQuantity());

        return new CartAddResult(savedCart.getId(), savedCartItem.getId(), savedCartItem.getQuantity());
    }
}

