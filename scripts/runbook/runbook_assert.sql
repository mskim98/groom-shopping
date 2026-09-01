-- 회차 종료 후 정합성 단언
-- 실행: psql -v couponId=1 -v stock=10000 -f scripts/runbook/runbook_assert.sql
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

-- ⚠ 단언 3 은 라운드마다 기대값이 반대다
--
-- S0 db-lock · S1 lua-then-lock : quantity 는 "남은 수량"이고 확정마다 감산되므로 0 이 되어야 한다
-- S2 kafka                      : quantity 는 "불변 발행 수량"이고 아무도 고치지 않으므로 :stock 그대로여야 한다
--
-- 감산을 없앤 것이 S2 의 본체다. 그 문장이 있는 한 같은 쿠폰의 모든 확정이 한 행을 두고 줄을 서므로,
-- 컨슈머를 몇 개로 늘려도 그 행이 처리량의 상한을 정한다
-- 그래서 여기서 0 을 기대하면 라운드가 정상일 때 실패하고, :stock 을 기대하면 앞 라운드에서 실패한다
-- 값으로 찍고 판정은 아래 안내에 따라 사람이 한다
\echo '[단언3] coupon.quantity - S0·S1 은 0, S2 는 :stock 이어야 한다'
SELECT quantity AS quantity,
       issued_count AS issued_count_frozen
  FROM coupon WHERE id = :couponId;

\echo '[참고] 발급 수 + 잔여 수량 - S0·S1 에서만 :stock 과 같다 (S2 는 stock 의 2배)'
SELECT (SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = :couponId)
     + (SELECT quantity  FROM coupon       WHERE id = :couponId) AS issued_plus_quantity;
