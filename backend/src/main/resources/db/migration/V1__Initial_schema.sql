-- ============================================
-- V1: Initial Schema Creation
-- Groom Shopping - 핵심 테이블 스키마
-- ============================================

-- 1. Users 테이블
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(30) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'ROLE_USER',
    grade VARCHAR(50) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);

-- 2. Product 테이블
-- ProductCategory: GENERAL, TICKET, RAFFLE
-- ProductStatus: AVAILABLE, OUT_OF_STOCK
CREATE TABLE IF NOT EXISTS product (
    id UUID PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    price INTEGER,
    stock INTEGER,
    is_active BOOLEAN DEFAULT TRUE,
    category VARCHAR(50),
    status VARCHAR(50) DEFAULT 'AVAILABLE',
    threshold_value INTEGER DEFAULT 10,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_product_status ON product(status);
CREATE INDEX idx_product_category ON product(category);

-- 3. Cart 테이블
CREATE TABLE IF NOT EXISTS cart (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_cart_user_id ON cart(user_id);

-- 4. CartItem 테이블 (entity: CartItemJpaEntity)
CREATE TABLE IF NOT EXISTS cart_item (
    id BIGSERIAL PRIMARY KEY,
    cart_id BIGINT NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES cart(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uq_cart_item_unique UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_cart_item_cart_id ON cart_item(cart_id);
CREATE INDEX idx_cart_item_product_id ON cart_item(product_id);

-- 5. Order 테이블 (entity: Order.java)
-- OrderStatus: PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
CREATE TABLE IF NOT EXISTS "Order" (
    id UUID PRIMARY KEY,
    "userId" BIGINT NOT NULL,
    "subTotal" INTEGER NOT NULL,
    "discountAmount" INTEGER,
    "totalAmount" INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    "couponId" BIGINT,
    "createdAt" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_user FOREIGN KEY ("userId") REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_order_user_id ON "Order"("userId");
CREATE INDEX idx_order_status ON "Order"(status);

-- 6. OrderItem 테이블 (entity: OrderItem.java)
CREATE TABLE IF NOT EXISTS "OrderItem" (
    id BIGSERIAL PRIMARY KEY,
    "orderId" UUID NOT NULL,
    "productId" UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    price INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    "subTotal" INTEGER NOT NULL,
    CONSTRAINT fk_order_item_order FOREIGN KEY ("orderId") REFERENCES "Order"(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_product FOREIGN KEY ("productId") REFERENCES product(id)
);

CREATE INDEX idx_order_item_order_id ON "OrderItem"("orderId");
CREATE INDEX idx_order_item_product_id ON "OrderItem"("productId");

-- 7. Payment 테이블 (entity: Payment.java, table name = payment)
-- PaymentStatus: PENDING, DONE, FAILED, CANCELLED
-- PaymentMethod: CARD, VIRTUAL_ACCOUNT
CREATE TABLE IF NOT EXISTS payment (
    id UUID PRIMARY KEY,
    "orderId" UUID NOT NULL UNIQUE,
    "userId" BIGINT NOT NULL,
    "paymentKey" VARCHAR(255),
    "transactionId" VARCHAR(255),
    last_transaction_key VARCHAR(200),
    amount INTEGER,
    balance_amount INTEGER,
    supplied_amount INTEGER,
    vat_amount INTEGER,
    tax_free_amount INTEGER,
    tax_exemption_amount INTEGER,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    method VARCHAR(50),
    order_name VARCHAR(255),
    customer_name VARCHAR(255),
    m_id VARCHAR(50),
    version VARCHAR(50),
    type VARCHAR(50) DEFAULT 'NORMAL',
    currency VARCHAR(10) DEFAULT 'KRW',
    use_escrow BOOLEAN DEFAULT FALSE,
    culture_expense BOOLEAN DEFAULT FALSE,
    is_partial_cancelable BOOLEAN DEFAULT TRUE,
    payment_method_details TEXT,
    receipt TEXT,
    checkout TEXT,
    failure_code VARCHAR(100),
    failure_message VARCHAR(255),
    requested_at TIMESTAMP,
    approved_at TIMESTAMP,
    canceled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_order FOREIGN KEY ("orderId") REFERENCES "Order"(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_user FOREIGN KEY ("userId") REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_payment_order_id ON payment("orderId");
CREATE INDEX idx_payment_user_id ON payment("userId");
CREATE INDEX idx_payment_status ON payment(status);

-- 8. Coupon 테이블 (entity: Coupon.java)
CREATE TABLE IF NOT EXISTS "Coupon" (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    quantity BIGINT NOT NULL,
    amount INTEGER NOT NULL,
    "maximumDiscount" INTEGER,
    "minimumCost" INTEGER,
    "isActive" BOOLEAN DEFAULT TRUE,
    type VARCHAR(50) NOT NULL,
    "expireDate" DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coupon_is_active ON "Coupon"("isActive");
CREATE INDEX idx_coupon_expire_date ON "Coupon"("expireDate");

-- 9. CouponIssue 테이블 (entity: CouponIssue.java)
CREATE TABLE IF NOT EXISTS "CouponIssue" (
    id BIGSERIAL PRIMARY KEY,
    "isActive" BOOLEAN DEFAULT TRUE,
    "createdAt" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt" TIMESTAMP,
    "userId" BIGINT NOT NULL,
    "couponId" BIGINT NOT NULL,
    CONSTRAINT fk_coupon_issue_user FOREIGN KEY ("userId") REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_coupon_issue_coupon FOREIGN KEY ("couponId") REFERENCES "Coupon"(id) ON DELETE CASCADE
);

CREATE INDEX idx_coupon_issue_user_id ON "CouponIssue"("userId");
CREATE INDEX idx_coupon_issue_coupon_id ON "CouponIssue"("couponId");

-- 10. Raffle 테이블 (entity: RaffleJpaEntity)
CREATE TABLE IF NOT EXISTS raffles (
    "raffleId" BIGSERIAL PRIMARY KEY,
    raffle_product_id UUID NOT NULL,
    winner_product_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    winners_count INTEGER NOT NULL,
    max_entries_per_user INTEGER NOT NULL,
    entry_start_at TIMESTAMP NOT NULL,
    entry_end_at TIMESTAMP NOT NULL,
    raffle_draw_at TIMESTAMP NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_raffles_status ON raffles(status);
CREATE INDEX idx_raffles_entry_start ON raffles(entry_start_at);
CREATE INDEX idx_raffles_entry_end ON raffles(entry_end_at);

-- 11. RaffleTicket 테이블 (entity: RaffleTicketJpaEntity)
CREATE TABLE IF NOT EXISTS raffle_tickets (
    raffle_ticket_id BIGSERIAL PRIMARY KEY,
    ticket_number BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    raffle_id BIGINT NOT NULL,
    CONSTRAINT fk_raffle_ticket_raffle FOREIGN KEY (raffle_id) REFERENCES raffles("raffleId") ON DELETE CASCADE,
    CONSTRAINT uq_raffle_ticket_unique UNIQUE (raffle_id, ticket_number)
);

CREATE INDEX idx_raffle_ticket_raffle_id ON raffle_tickets(raffle_id);
CREATE INDEX idx_raffle_ticket_user_id ON raffle_tickets(user_id);

-- 12. RaffleWinner 테이블 (entity: RaffleWinnerJpaEntity)
CREATE TABLE IF NOT EXISTS raffle_winners (
    raffle_winner_id BIGSERIAL PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    rank INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    raffle_ticket_id BIGINT NOT NULL,
    CONSTRAINT fk_raffle_winner_ticket FOREIGN KEY (raffle_ticket_id) REFERENCES raffle_tickets(raffle_ticket_id) ON DELETE CASCADE
);

CREATE INDEX idx_raffle_winner_status ON raffle_winners(status);
CREATE INDEX idx_raffle_winner_raffle_ticket_id ON raffle_winners(raffle_ticket_id);

-- 13. Notification 테이블 (entity: NotificationJpaEntity)
CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    current_stock INTEGER,
    threshold_value INTEGER,
    message TEXT,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT NOT NULL,
    product_id UUID NOT NULL
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_product_id ON notifications(product_id);
CREATE INDEX idx_notifications_is_read ON notifications(is_read);

-- ============================================
-- V1 마이그레이션 완료
-- 모든 핵심 테이블 생성:
-- - Users, Product, Cart, CartItem
-- - Order, OrderItem, Payment
-- - Coupon, CouponIssue
-- - Raffle, RaffleTicket, RaffleWinner
-- - Notification
-- RefreshToken은 Redis에 저장되므로 제외
-- ============================================
