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

-- 4. CartItem 테이블
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

-- 5. Order 테이블 (테이블명: "Order" - 예약어이므로 큰따옴표 사용)
CREATE TABLE IF NOT EXISTS "Order" (
    id UUID PRIMARY KEY,
    userId BIGINT NOT NULL,
    subTotal INTEGER NOT NULL,
    discountAmount INTEGER,
    totalAmount INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    couponId BIGINT,
    createdAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_user FOREIGN KEY (userId) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_order_user_id ON "Order"(userId);
CREATE INDEX idx_order_status ON "Order"(status);

-- 6. OrderItem 테이블
CREATE TABLE IF NOT EXISTS order_item (
    id BIGSERIAL PRIMARY KEY,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    product_name VARCHAR(255),
    product_price INTEGER,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES "Order"(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product(id)
);

CREATE INDEX idx_order_item_order_id ON order_item(order_id);
CREATE INDEX idx_order_item_product_id ON order_item(product_id);

-- 7. Payment 테이블
CREATE TABLE IF NOT EXISTS payment (
    id UUID PRIMARY KEY,
    orderId UUID NOT NULL UNIQUE,
    userId BIGINT NOT NULL,
    payment_key VARCHAR(255),
    transaction_id VARCHAR(255),
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
    CONSTRAINT fk_payment_order FOREIGN KEY (orderId) REFERENCES "Order"(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_user FOREIGN KEY (userId) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_payment_order_id ON payment(orderId);
CREATE INDEX idx_payment_user_id ON payment(userId);
CREATE INDEX idx_payment_status ON payment(status);

-- ============================================
-- 마이그레이션 완료
-- ============================================
