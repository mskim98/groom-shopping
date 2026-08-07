-- =====================================================================
-- 부하 테스트 Mock 데이터 정리(삭제)
--   docker exec -i dev-db psql -U dev -d shopping_db_dev \
--     < backend/scripts/loadtest/cleanup_mock.sql
--
-- 삭제 순서가 중요하다 (FK 제약):
--  1) loadtest 유저 삭제 → ON DELETE CASCADE 로 orders / order_item / coupon_issue / payment 까지 함께 삭제
--  2) order_item 이 사라진 뒤 loadtest product 삭제 (product FK 는 CASCADE 아님)
--  3) loadtest coupon 삭제
-- =====================================================================
\timing on

\echo '>>> loadtest 유저 삭제 (orders/order_item/coupon_issue/payment CASCADE)'
DELETE FROM users WHERE email LIKE 'loadtest_%';

\echo '>>> loadtest product 삭제'
DELETE FROM product WHERE name LIKE 'LoadTest %';

\echo '>>> loadtest coupon 삭제'
DELETE FROM coupon WHERE name LIKE 'LoadTest %';

\echo '>>> 남은 건수 확인 (모두 0 이어야 함):'
SELECT
    (SELECT count(*) FROM users   WHERE email LIKE 'loadtest_%') AS users,
    (SELECT count(*) FROM product WHERE name  LIKE 'LoadTest %') AS products,
    (SELECT count(*) FROM coupon  WHERE name  LIKE 'LoadTest %') AS coupons;