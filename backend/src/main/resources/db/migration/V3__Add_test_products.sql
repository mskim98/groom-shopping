-- ============================================
-- V3: Add Test Products
-- k6 로드 테스트용 상품 데이터 (50개)
-- ============================================

-- 고정 UUID (k6 테스트에서 사용)
INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value, created_at, updated_at) VALUES
('550e8400-e29b-41d4-a716-446655440000', 'Premium Laptop Pro', 'High-performance laptop for developers', 1500000, 50, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440001', 'Wireless Mouse Ultra', 'Silent wireless mouse with long battery', 45000, 200, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440002', 'USB-C Hub Pro', 'Multi-port USB-C hub with power delivery', 89000, 150, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440003', 'Mechanical Keyboard RGB', 'Mechanical keyboard with RGB lighting', 189000, 100, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440004', '4K Monitor 32inch', 'Ultra HD 4K monitor for professionals', 799000, 30, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),

-- 추가 상품들 (다양한 카테고리)
('550e8400-e29b-41d4-a716-446655440005', 'Casual T-Shirt', 'Comfortable 100% cotton t-shirt', 25000, 500, TRUE, 'FASHION', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440006', 'Denim Jeans Blue', 'Classic blue denim jeans', 65000, 300, TRUE, 'FASHION', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440007', 'Winter Jacket', 'Warm winter jacket with insulation', 189000, 100, TRUE, 'FASHION', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440008', 'Running Shoes Sport', 'Lightweight running shoes with cushioning', 129000, 150, TRUE, 'FASHION', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440009', 'Leather Wallet Brown', 'Premium leather wallet', 45000, 200, TRUE, 'FASHION', 'AVAILABLE', 10, NOW(), NOW()),

('550e8400-e29b-41d4-a716-446655440010', 'Organic Coffee Beans', 'Premium organic coffee beans 1kg', 15000, 800, TRUE, 'FOOD', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440011', 'Green Tea Premium', 'High-quality green tea 500g', 12000, 600, TRUE, 'FOOD', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440012', 'Dark Chocolate 72%', 'Premium dark chocolate bar', 8000, 1000, TRUE, 'FOOD', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440013', 'Honey Natural Pure', 'Pure honey 500ml', 18000, 400, TRUE, 'FOOD', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440014', 'Almond Butter Organic', 'Organic almond butter 300g', 16000, 350, TRUE, 'FOOD', 'AVAILABLE', 10, NOW(), NOW()),

-- 추가 전자제품
('550e8400-e29b-41d4-a716-446655440015', 'Wireless Headphones', 'Noise-cancelling wireless headphones', 259000, 80, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440016', 'Portable SSD 1TB', 'Portable SSD with fast transfer speeds', 199000, 120, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440017', 'Webcam 4K Pro', 'Professional 4K webcam', 179000, 90, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440018', 'Phone Charger Fast', 'Fast phone charger 65W', 35000, 400, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW()),
('550e8400-e29b-41d4-a716-446655440019', 'Cable HDMI Premium', 'High-quality HDMI cable', 18000, 800, TRUE, 'ELECTRONICS', 'AVAILABLE', 10, NOW(), NOW());

-- 동적 UUID로 추가 상품들 (30개 더)
INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value, created_at, updated_at)
SELECT
    gen_random_uuid() as id,
    'Product ' || (ROW_NUMBER() OVER (ORDER BY RANDOM())) + 20 as name,
    'High-quality product for testing load testing' as description,
    CAST(FLOOR(RANDOM() * 500000 + 10000) AS INTEGER) as price,
    CAST(FLOOR(RANDOM() * 500 + 50) AS INTEGER) as stock,
    TRUE as is_active,
    CASE WHEN ROW_NUMBER() OVER (ORDER BY RANDOM()) % 3 = 0 THEN 'ELECTRONICS'
         WHEN ROW_NUMBER() OVER (ORDER BY RANDOM()) % 3 = 1 THEN 'FASHION'
         ELSE 'FOOD' END as category,
    'AVAILABLE' as status,
    10 as threshold_value,
    NOW() as created_at,
    NOW() as updated_at
FROM
    generate_series(1, 30);

-- ============================================
-- V3 마이그레이션 완료
-- 총 50개 상품 생성
-- - 20개는 고정 UUID (k6 테스트에서 사용 가능)
-- - 30개는 동적 UUID (부하 테스트용)
-- - 다양한 가격대와 재고 수량
-- - 세 가지 카테고리 (ELECTRONICS, FASHION, FOOD)
-- ============================================
