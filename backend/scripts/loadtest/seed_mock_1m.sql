-- =====================================================================
-- 부하 테스트용 Mock 데이터 대량 생성 (product / orders+order_item / coupon_issue)
-- 방식: PostgreSQL generate_series 기반 INSERT ... SELECT (DB 내부 대량 생성)
--
-- 실행 (docker-compose.dev.yml 의 db 컨테이너 = dev-db):
--   docker exec -i dev-db psql -U dev -d shopping_db_dev \
--     < backend/scripts/loadtest/seed_mock_1m.sql
--
-- 정리(삭제)는 같은 폴더의 cleanup_mock.sql 사용.
-- 모든 mock 데이터는 'LoadTest %' 이름 / 'loadtest_%' 이메일로 태깅되어 식별/삭제가 쉽다.
-- =====================================================================

-- ── 생성 건수 설정 (필요 시 여기만 바꾸면 됨) ───────────────────────────
\set n_users        1000000
\set n_products     1000000
\set n_coupons      100
\set n_orders       1000000
\set n_coupon_issue 1000000

-- 대량 INSERT 동안 임시로 메모리/속도 튜닝 (이 세션에만 적용)
SET synchronous_commit = off;
SET maintenance_work_mem = '512MB';
SET work_mem = '256MB';

\timing on
\echo '>>> [1/6] users 생성 중...'

-- ── 1) users : orders / coupon_issue 의 FK 부모 ───────────────────────
-- email UNIQUE 이므로 'loadtest_<n>@test.com' 로 충돌 없이 생성. 재실행 대비 ON CONFLICT.
INSERT INTO users (email, password, name, role, grade, created_at, updated_at)
SELECT 'loadtest_' || g || '@test.com',
       '$2a$10$0123456789012345678901uXdummybcryptpasswordhashvaluexx', -- 더미 bcrypt
       'user' || g,
       'ROLE_USER',
       'NORMAL',
       now(),
       now()
FROM generate_series(1, :n_users) AS g
ON CONFLICT (email) DO NOTHING;

\echo '>>> [2/6] product 생성 중...'

-- ── 2) product : order_item 의 FK 부모 ────────────────────────────────
-- id 는 UUID → gen_random_uuid() (PostgreSQL 13+ 내장). 가격/재고/카테고리 분산.
INSERT INTO product
(id, name, description, price, stock, is_active, category, status,
 threshold_value, image_url, version, created_at, updated_at)
SELECT gen_random_uuid(),
       'LoadTest Product ' || g,
       'load test product description ' || g,
       (1000 + floor(random() * 1000000))::int,                                                    -- 가격 1,000 ~ 1,001,000
       floor(random() * 1000)::int,                                                                -- 재고 0 ~ 999
       TRUE,
       (ARRAY ['GENERAL','TICKET','RAFFLE'])[1 + floor(random() * 3)::int],                        -- 카테고리 랜덤
       (ARRAY ['AVAILABLE','AVAILABLE','AVAILABLE','OUT_OF_STOCK'])[1 + floor(random() * 4)::int], -- 75% 판매중
       10,
       'https://picsum.photos/seed/' || g || '/300/300',
       0,
       now(),
       now()
FROM generate_series(1, :n_products) AS g;

\echo '>>> [3/6] coupon(부모) 생성 중...'

-- ── 3) coupon : coupon_issue 의 FK 부모 (소량) ────────────────────────
INSERT INTO coupon
(name, description, quantity, amount, maximum_discount, minimum_cost,
 is_active, type, expire_date, created_at, updated_at)
SELECT 'LoadTest Coupon ' || g,
       'load test coupon ' || g,
       1000000,
       (1000 + floor(random() * 9000))::int, -- 할인액 1,000 ~ 10,000
       20000,
       10000,
       TRUE,
       -- CouponType enum 값만 사용(FIXED 는 enum 에 없어 발급 시 500). DISCOUNT=정액, PERCENT=정률
       (ARRAY ['DISCOUNT','PERCENT'])[1 + floor(random() * 2)::int],
       (now() + interval '30 days')::date,
       now(),
       now()
FROM generate_series(1, :n_coupons) AS g;

-- ── FK 연결용 임시 인덱스 테이블 (배열 대신 row_number 매핑: 메모리 가벼움) ──
-- 방금 만든 loadtest 행들만 1..N 순번을 매겨두면, 모듈러(%)로 균등 분배해 조인할 수 있다.
DROP TABLE IF EXISTS tmp_user;
DROP TABLE IF EXISTS tmp_prod;
DROP TABLE IF EXISTS tmp_coupon;
DROP TABLE IF EXISTS tmp_order;

CREATE TEMP TABLE tmp_user AS
SELECT (row_number() OVER (ORDER BY id)) AS rn, id
FROM users
WHERE email LIKE 'loadtest_%';
CREATE TEMP TABLE tmp_prod AS
SELECT (row_number() OVER (ORDER BY id)) AS rn, id, name, price
FROM product
WHERE name LIKE 'LoadTest %';
CREATE TEMP TABLE tmp_coupon AS
SELECT (row_number() OVER (ORDER BY id)) AS rn, id
FROM coupon
WHERE name LIKE 'LoadTest %';
CREATE INDEX ON tmp_user (rn);
CREATE INDEX ON tmp_prod (rn);
CREATE INDEX ON tmp_coupon (rn);

\echo '>>> [4/6] orders 생성 중...'

-- ── 4) orders : g 번째 주문을 (g % 유저수) 번째 loadtest 유저에 배정 ────
INSERT INTO orders
(id, user_id, sub_total, discount_amount, total_amount, status, coupon_id, created_at, updated_at)
SELECT gen_random_uuid(),
       u.id,
       0,
       0,
       0,                                       -- 금액은 5단계에서 order_item 으로부터 채움
       (ARRAY ['PENDING','CONFIRMED','SHIPPING','DELIVERED','COMPLETED','CANCELLED'])[1 + floor(random() * 6)::int],
       NULL,
       now() - (random() * interval '90 days'), -- 최근 90일 분산
       now()
FROM generate_series(1, :n_orders) AS g
         JOIN tmp_user u ON u.rn = 1 + (g % (SELECT count(*) FROM tmp_user));

\echo '>>> [5/6] order_item 생성 + 주문 금액 갱신 중...'

-- 방금 만든 loadtest 주문만 순번 매김 (user 가 loadtest 유저인 주문)
CREATE TEMP TABLE tmp_order AS
SELECT (row_number() OVER (ORDER BY o.id)) AS rn, o.id
FROM orders o
         JOIN tmp_user u ON u.id = o.user_id;
CREATE INDEX ON tmp_order (rn);

-- 주문 1건당 상품 1개 스냅샷(상품명/가격 복사 = 도메인 규칙과 동일)
INSERT INTO order_item (order_id, product_id, name, price, quantity, sub_total)
SELECT ord.id,
       p.id,
       p.name,
       p.price,
       q.qty,
       p.price * q.qty
FROM tmp_order ord
         JOIN tmp_prod p ON p.rn = 1 + (ord.rn % (SELECT count(*) FROM tmp_prod))
         CROSS JOIN LATERAL (SELECT 1 + (ord.rn % 5) AS qty) q;

-- 주문 합계 = 해당 order_item 의 sub_total 로 정합성 맞춤
UPDATE orders o
SET sub_total    = oi.sub_total,
    total_amount = oi.sub_total
FROM order_item oi
         JOIN tmp_order t ON t.id = oi.order_id
WHERE o.id = t.id;

\echo '>>> [6/6] coupon_issue 생성 중...'

-- ── 6) coupon_issue : 유저 × 쿠폰 균등 분배 ───────────────────────────
INSERT INTO coupon_issue (is_active, created_at, user_id, coupon_id)
SELECT TRUE,
       now() - (random() * interval '30 days'),
       u.id,
       c.id
FROM generate_series(1, :n_coupon_issue) AS g
         JOIN tmp_user u ON u.rn = 1 + (g % (SELECT count(*) FROM tmp_user))
         JOIN tmp_coupon c ON c.rn = 1 + (g % (SELECT count(*) FROM tmp_coupon));

-- 임시 테이블 정리
DROP TABLE IF EXISTS tmp_user;
DROP TABLE IF EXISTS tmp_prod;
DROP TABLE IF EXISTS tmp_coupon;
DROP TABLE IF EXISTS tmp_order;

\echo '>>> ANALYZE (쿼리 플래너 통계 갱신 — 부하 테스트 정확도에 중요)'
ANALYZE users;
ANALYZE product;
ANALYZE orders;
ANALYZE order_item;
ANALYZE coupon;
ANALYZE coupon_issue;

\echo '>>> 완료. 건수 확인:'
SELECT (SELECT count(*) FROM users WHERE email LIKE 'loadtest_%')  AS users,
       (SELECT count(*) FROM product WHERE name LIKE 'LoadTest %') AS products,
       (SELECT count(*) FROM orders)                               AS orders_total,
       (SELECT count(*) FROM order_item)                           AS order_items_total,
       (SELECT count(*) FROM coupon_issue)                         AS coupon_issues_total;