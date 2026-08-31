-- 회차 종료 후 정합성 단언
-- 실행: psql -v couponId=1 -v stock=10000 -f backend/docs/measurements/runbook_assert.sql
--
-- 성능만 보고 정확성 회귀를 놓치지 않으려는 것이다
-- 기대값과 다르면 그 회차는 성능 수치도 쓰지 않는다

\set ON_ERROR_STOP on

\echo '[단언1] 발급 수 == :stock  (초과 발급 0 이면서 미달 0)'
SELECT COUNT(*) AS issued FROM coupon_issue WHERE coupon_id = :couponId;

\echo '[단언2] 중복 사용자 == 0'
SELECT COUNT(*) AS dup_users FROM (
  SELECT user_id
    FROM coupon_issue
   WHERE coupon_id = :couponId
   GROUP BY user_id
  HAVING COUNT(*) > 1) t;

\echo '[단언3] 잔여 수량 == 0'
SELECT quantity AS remaining FROM coupon WHERE id = :couponId;

\echo '[참고] 발급 수 + 잔여 수량 == :stock 이어야 DB 한쪽만 어긋난 상태가 아니다'
SELECT (SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = :couponId)
     + (SELECT quantity  FROM coupon       WHERE id = :couponId) AS issued_plus_remaining;
