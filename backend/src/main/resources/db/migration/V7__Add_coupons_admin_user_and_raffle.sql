-- ============================================
-- V7: Add Coupons, Admin User and Raffle Data
-- ============================================

-- 1. 쿠폰 데이터 추가
-- CouponType: PERCENT, DISCOUNT, MIN_COST_AMOUNT, MAX_DISCOUNT_PERCENT
INSERT INTO coupon (name, description, quantity, amount, maximum_discount, minimum_cost, is_active, type, expire_date, created_at, updated_at) VALUES
-- PERCENT 타입 (할인율)
('Summer Sale 10%', '여름 세일 10% 할인', 100, 10, 50000, 50000, TRUE, 'PERCENT', DATE '2025-12-31', NOW(), NOW()),
('New Member Welcome', '신규 회원 환영 15% 할인', 500, 15, 100000, 30000, TRUE, 'PERCENT', DATE '2025-12-31', NOW(), NOW()),

-- DISCOUNT 타입 (고정 할인액)
('5000 Won Discount', '5,000원 할인', 200, 5000, NULL, 20000, TRUE, 'DISCOUNT', DATE '2025-12-31', NOW(), NOW()),
('10000 Won Discount', '10,000원 할인', 150, 10000, NULL, 50000, TRUE, 'DISCOUNT', DATE '2025-12-31', NOW(), NOW()),

-- MIN_COST_AMOUNT 타입 (최소 구매금액 할인)
('Minimum 30000 Discount', '30,000원 이상 구매시 3,000원 할인', 300, 3000, NULL, 30000, TRUE, 'MIN_COST_AMOUNT', DATE '2025-12-31', NOW(), NOW()),

-- MAX_DISCOUNT_PERCENT 타입 (최대 할인율)
('Max Discount Percent', '최대 20% 할인', 100, 20, 15000, 50000, TRUE, 'MAX_DISCOUNT_PERCENT', DATE '2025-12-31', NOW(), NOW());

-- 2. 테스트 관리자 사용자 추가 (admin@admin.com)
-- password: admin123 (bcrypt hashed)
INSERT INTO users (email, password, name, role, grade, created_at, updated_at) VALUES
('admin@admin.com', '$2a$10$EnQlNMwfVn0j1rvp0QVCLuGGNCvfHvEyDEBfSPB8LXJPPwxmP/zR.', 'Test Admin', 'ROLE_ADMIN', 'GOLD', NOW(), NOW());

-- 3. admin@admin.com 사용자의 카트 생성
INSERT INTO cart (user_id, created_at, updated_at)
SELECT id, NOW(), NOW() FROM users WHERE email = 'admin@admin.com';

-- 4. admin@admin.com 사용자의 카트에 상품 추가
-- 상품 조회 후 상위 5개 제품을 카트에 추가
INSERT INTO cart_item (cart_id, product_id, quantity, created_at, updated_at)
SELECT
    c.id,
    p.id,
    1,
    NOW(),
    NOW()
FROM cart c
JOIN users u ON c.user_id = u.id
CROSS JOIN (
    SELECT id FROM product
    WHERE is_active = TRUE
    ORDER BY created_at
    LIMIT 5
) p
WHERE u.email = 'admin@admin.com';

-- 5. Raffle 데이터 추가
-- 먼저 TICKET과 RAFFLE 카테고리의 제품 조회
INSERT INTO raffles (raffle_product_id, winner_product_id, title, description, winners_count, max_entries_per_user, entry_start_at, entry_end_at, raffle_draw_at, status, created_at, updated_at)
SELECT
    ticket_product.id,
    raffle_product.id,
    'Monthly Premium Raffle #' || ROW_NUMBER() OVER (ORDER BY ticket_product.id),
    'Limited edition monthly raffle event - Enter to win exclusive prizes!',
    5,
    3,
    NOW(),
    DATE '2025-12-31',
    DATE '2025-12-25',
    'ACTIVE',
    NOW(),
    NOW()
FROM (
    SELECT id FROM product
    WHERE category = 'TICKET'
    AND is_active = TRUE
    LIMIT 3
) ticket_product
CROSS JOIN (
    SELECT id FROM product
    WHERE category = 'RAFFLE'
    AND is_active = TRUE
    LIMIT 3
) raffle_product;

-- ============================================
-- V7 마이그레이션 완료
-- - 쿠폰 6개 생성 (다양한 CouponType)
-- - 테스트 관리자 계정 생성 (admin@admin.com)
-- - 관리자 사용자 카트 생성 및 상품 5개 추가
-- - Raffle 3개 생성 (준비 상태)
-- ============================================
