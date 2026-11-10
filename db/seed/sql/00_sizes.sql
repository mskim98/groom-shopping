-- 규모 변수와 결정적 난수 함수. build-template.sh 와 verify.sh 가 같은 psql 세션 맨 앞에 이어 붙여 쓴다(-v scale=<배수>)
-- 목표 행 수는 scale 1 에서 사용자 10만 · 상품 5만(플래시 20) · 주문 30만 · 주문 항목 약 60만 · 결제 30만 · 보상 500 · 쿠폰 20 · 쿠폰 발급 20만 · 장바구니 항목 15만 · 알림 10만이다
-- 소수 상태(READY · PENDING · 보상 상태 8종 · 닫힌 쿠폰)가 작은 배수에서도 남도록 아래 하한을 둔다
SELECT
  GREATEST(1, round(100000 * :scale::numeric))::bigint AS n_users,
  round(50000 * :scale::numeric)::bigint AS n_products,
  GREATEST(1, round(20 * :scale::numeric))::bigint AS n_flash,
  round(300000 * :scale::numeric)::bigint AS n_orders,
  GREATEST(1, round(50 * :scale::numeric))::bigint AS n_ready,
  GREATEST(12, round(500 * :scale::numeric))::bigint AS n_comp,
  GREATEST(1, round(20 * :scale::numeric))::bigint AS n_comp_pending,
  GREATEST(1, round(5 * :scale::numeric))::bigint AS n_comp_each,
  GREATEST(4, round(20 * :scale::numeric))::bigint AS n_coupon,
  round(200000 * :scale::numeric)::bigint AS n_issue,
  round(150000 * :scale::numeric)::bigint AS n_cartitem,
  round(100000 * :scale::numeric)::bigint AS n_notif
\gset
SELECT :n_products - :n_flash AS n_normal,
       GREATEST(ceil(0.01 * :n_products), :n_flash)::bigint AS n_hot,
       :n_orders * 2 AS n_items
\gset

-- 키 k 와 소금 salt 로 [0,1) 난수를 낸다. 같은 입력은 언제나 같은 값이라 두 번 빌드해도 행 수가 같다
CREATE FUNCTION pg_temp.h(k bigint, salt text) RETURNS float8 LANGUAGE sql IMMUTABLE AS
$$ SELECT (('x' || substr(md5(k::text || ':' || salt), 1, 8))::bit(32)::bigint)::float8 / 4294967296.0 $$;

-- TIMESTAMP 열은 애플리케이션이 Asia/Seoul 로 읽고 쓴다(spring.jpa.properties.hibernate.jdbc.time_zone)
SELECT (now() AT TIME ZONE 'Asia/Seoul') AS now_s
\gset
