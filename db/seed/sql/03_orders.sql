-- 주문 · 주문 항목. 항목을 먼저 임시 표(seed_item)에 만들어 합계 · 플래시 포함 여부를 주문과 결제가 같이 쓴다
-- 항목 수는 주문 번호 k 의 10 주기 [1,1,1,1,2,2,2,3,3,4] 라 평균이 정확히 2.0 이다
-- 상품 쏠림: 항목의 절반은 상위 1 %(n_hot 개, 플래시 전부 포함)에서, 나머지 절반은 전체에서 고른다
-- 사용자 쏠림: 주문의 절반은 사용자 상위 10 % 가 낸다
CREATE TEMP TABLE seed_item AS
SELECT x.k, x.j, sp.p, sp.id AS product_id, sp.name, sp.price, x.qty
  FROM (SELECT k, j,
               CASE WHEN pg_temp.h(k * 8 + j, 'hot') < 0.5
                    THEN 1 + floor(pg_temp.h(k * 8 + j, 'hotp') * :n_hot)::int
                    ELSE 1 + floor(pg_temp.h(k * 8 + j, 'allp') * :n_products)::int END AS p,
               CASE WHEN pg_temp.h(k * 8 + j, 'q') < 0.6 THEN 1 WHEN pg_temp.h(k * 8 + j, 'q') < 0.9 THEN 2 ELSE 3 END AS qty
          FROM generate_series(1, :n_orders) k
          CROSS JOIN LATERAL generate_series(1, (ARRAY[1,1,1,1,2,2,2,3,3,4])[1 + (k % 10)]) j) x
  JOIN seed_prod sp ON sp.p = x.p;
CREATE INDEX ON seed_item (k);

-- 결제 상태: DONE 95 % · CANCELED 2 % · FAILED 1 % · ABORTED 1 % · PENDING 1 %, 그리고 플래시 항목이 든 주문 중 정확히 n_ready 건이 READY
CREATE TEMP TABLE seed_pay AS
SELECT k, CASE WHEN r < 0.95 THEN 'DONE' WHEN r < 0.97 THEN 'CANCELED' WHEN r < 0.98 THEN 'FAILED'
               WHEN r < 0.99 THEN 'ABORTED' ELSE 'PENDING' END AS st
  FROM (SELECT k, pg_temp.h(k, 'pay') AS r FROM generate_series(1, :n_orders) k) x;
UPDATE seed_pay SET st = 'READY'
 WHERE k IN (SELECT k FROM (SELECT f.k, row_number() OVER (ORDER BY pg_temp.h(f.k, 'ready')) AS rn
                              FROM (SELECT DISTINCT k FROM seed_item WHERE p <= :n_flash) f) r
              WHERE rn <= :n_ready);
CREATE UNIQUE INDEX ON seed_pay (k);

-- 주문 임시 표: 시각은 최근 30 일(READY 는 한 시간 전)
CREATE TEMP TABLE seed_ord AS
SELECT o.k, md5('seedord:' || o.k)::uuid AS id,
       :u_base + CASE WHEN pg_temp.h(o.k, 'heavy') < 0.5
                      THEN 1 + floor(pg_temp.h(o.k, 'hu') * 0.1 * :n_users)::int
                      ELSE 1 + floor(pg_temp.h(o.k, 'au') * :n_users)::int END AS user_id,
       s.sub_total, p.st AS pay_st,
       CASE WHEN p.st = 'READY' THEN :'now_s'::timestamp - interval '1 hour'
            ELSE :'now_s'::timestamp - pg_temp.h(o.k, 'ocreated') * interval '30 days' END AS created_at
  FROM generate_series(1, :n_orders) o(k)
  JOIN seed_pay p ON p.k = o.k
  JOIN (SELECT k, SUM(price * qty)::int AS sub_total FROM seed_item GROUP BY k) s ON s.k = o.k;
CREATE UNIQUE INDEX ON seed_ord (k);

BEGIN;
INSERT INTO orders (id, user_id, sub_total, discount_amount, total_amount, status, coupon_id, created_at, updated_at)
SELECT id, user_id, sub_total, 0, sub_total,
       CASE pay_st
         WHEN 'DONE' THEN CASE WHEN pg_temp.h(k, 'ost') < 0.9 THEN 'CONFIRMED'
                               ELSE (ARRAY['PREPARING', 'SHIPPING', 'DELIVERED', 'COMPLETED'])[1 + floor(pg_temp.h(k, 'ost2') * 4)::int] END
         WHEN 'CANCELED' THEN 'CANCELLED'
         ELSE 'PENDING' END,
       NULL, created_at, created_at
  FROM seed_ord;
COMMIT;

BEGIN;
INSERT INTO order_item (id, order_id, product_id, name, price, quantity, sub_total)
SELECT row_number() OVER (ORDER BY i.k, i.j), o.id, i.product_id, i.name, i.price, i.qty, i.price * i.qty
  FROM seed_item i JOIN seed_ord o ON o.k = i.k;
COMMIT;
