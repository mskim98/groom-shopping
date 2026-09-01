-- 회차 시작 전 초기화
-- 실행: psql -v couponId=1 -v stock=10000 -f backend/docs/measurements/runbook_init.sql
--
-- 앞 회차의 발급 이력이 남으면 Lua 의 발급자 SET 과 DB 가 어긋난 채로 시작해
-- 중복 카운터가 오염된다. 재고를 되돌리기 전에 이력부터 비운다

\set ON_ERROR_STOP on

TRUNCATE TABLE coupon_issue RESTART IDENTITY CASCADE;

-- 만료일이 지나면 발급이 게이트가 아니라 유효성 검사에서 막혀 라운드 비교가 성립하지 않는다
-- issued_count 를 NULL 로 되돌린다. 앞 회차의 마감이 굳혀 놓은 값이 남아 있으면
-- 이번 회차가 "이미 마감된 쿠폰" 으로 보인다
UPDATE coupon
   SET quantity     = :stock,
       issued_count = NULL,
       is_active    = TRUE,
       expire_date  = CURRENT_DATE + 30,
       updated_at   = NOW()
 WHERE id = :couponId;

SELECT id, quantity, issued_count, is_active, expire_date FROM coupon WHERE id = :couponId;
