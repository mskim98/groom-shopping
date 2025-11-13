-- ============================================
-- V3: Add Test Products
-- k6 로드 테스트용 상품 데이터 (50개)
-- ProductCategory: GENERAL, TICKET, RAFFLE (3가지만)
-- ProductStatus: AVAILABLE, OUT_OF_STOCK
-- ============================================

-- 고정 UUID (k6 테스트에서 사용)
INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value, created_at, updated_at) VALUES
('550e8400-e29b-41d4-a716-446655440000', 'Premium Laptop Pro', 'High-performance laptop for developers', 1500000, 50, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440001', 'Wireless Mouse Ultra', 'Silent wireless mouse with long battery', 45000, 200, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440002', 'USB-C Hub Pro', 'Multi-port USB-C hub with power delivery', 89000, 150, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440003', 'Mechanical Keyboard RGB', 'Mechanical keyboard with RGB lighting', 189000, 100, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440004', '4K Monitor 32inch', 'Ultra HD 4K monitor for professionals', 799000, 30, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),

-- GENERAL 상품들 (추가)
('550e8400-e29b-41d4-a716-446655440005', 'Casual T-Shirt', 'Comfortable 100% cotton t-shirt', 25000, 500, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440006', 'Denim Jeans Blue', 'Classic blue denim jeans', 65000, 300, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440007', 'Winter Jacket', 'Warm winter jacket with insulation', 189000, 100, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440008', 'Running Shoes Sport', 'Lightweight running shoes with cushioning', 129000, 150, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440009', 'Leather Wallet Brown', 'Premium leather wallet', 45000, 200, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),

('550e8400-e29b-41d4-a716-446655440010', 'Organic Coffee Beans', 'Premium organic coffee beans 1kg', 15000, 800, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440011', 'Green Tea Premium', 'High-quality green tea 500g', 12000, 600, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440012', 'Dark Chocolate 72%', 'Premium dark chocolate bar', 8000, 1000, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440013', 'Honey Natural Pure', 'Pure honey 500ml', 18000, 400, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440014', 'Almond Butter Organic', 'Organic almond butter 300g', 16000, 350, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),

('550e8400-e29b-41d4-a716-446655440015', 'Wireless Headphones', 'Noise-cancelling wireless headphones', 259000, 80, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440016', 'Portable SSD 1TB', 'Portable SSD with fast transfer speeds', 199000, 120, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440017', 'Webcam 4K Pro', 'Professional 4K webcam', 179000, 90, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440018', 'Phone Charger Fast', 'Fast phone charger 65W', 35000, 400, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440019', 'Cable HDMI Premium', 'High-quality HDMI cable', 18000, 800, TRUE, 'GENERAL', 'AVAILABLE', 10, NOW(), NOW());

-- TICKET 상품들 (10개)
INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value, created_at, updated_at)
SELECT
    gen_random_uuid(),
    'Raffle Ticket #' || (ROW_NUMBER() OVER (ORDER BY RANDOM())),
    'Entry ticket for monthly raffle draw',
    10000,
    100,
    TRUE,
    'TICKET',
    'AVAILABLE',
    5,
    NOW(),
    NOW()
FROM generate_series(1, 10);

-- RAFFLE 상품들 (10개)
INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value, created_at, updated_at)
SELECT
    gen_random_uuid(),
    'Raffle Prize Item #' || (ROW_NUMBER() OVER (ORDER BY RANDOM())),
    'Limited edition prize for raffle winners',
    50000,
    CAST(FLOOR(RANDOM() * 20 + 1) AS INTEGER),
    TRUE,
    'RAFFLE',
    'AVAILABLE',
    3,
    NOW(),
    NOW()
FROM generate_series(1, 10);

-- ============================================
-- V3 마이그레이션 완료
-- 총 50개 상품 생성
-- - 20개: 고정 UUID (GENERAL) - k6 테스트 직접 사용 가능
-- - 10개: 동적 UUID (TICKET) - 추첨 티켓
-- - 10개: 동적 UUID (RAFFLE) - 증정 상품
-- - 추가: 동적 UUID 상품들 (필요시)
-- ============================================
