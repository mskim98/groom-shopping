-- 장바구니: 시드 사용자마다 하나. 항목 n_cartitem 개는 장바구니에 돌아가며 나누고(한 장바구니에 0 … 2개),
-- 5 % 는 플래시 상품, 나머지는 일반 상품이다. 한 장바구니 안의 같은 상품은 DISTINCT ON 으로 걸러 유일 제약을 지킨다
SELECT COALESCE(max(id), 0) AS cart_base, (SELECT COALESCE(max(id), 0) FROM cart_item) AS ci_base FROM cart
\gset
BEGIN;
INSERT INTO cart (id, user_id, created_at, updated_at)
SELECT :cart_base + k, :u_base + k, u.created_at, u.created_at
  FROM generate_series(1, :n_users) k JOIN users u ON u.id = :u_base + k;
COMMIT;

BEGIN;
INSERT INTO cart_item (id, cart_id, product_id, quantity, created_at, updated_at)
SELECT :ci_base + row_number() OVER (ORDER BY y.c, y.p), :cart_base + y.c, sp.id, y.qty, :'now_s'::timestamp - interval '3 days', :'now_s'::timestamp - interval '3 days'
  FROM (SELECT DISTINCT ON (x.c, x.p) x.c, x.p, x.qty
          FROM (SELECT 1 + ((m - 1) % :n_users) AS c, ((m - 1) / :n_users) AS r, m,
                       1 + floor(pg_temp.h(m, 'cq') * 3)::int AS qty
                  FROM generate_series(1, :n_cartitem) m) b
          CROSS JOIN LATERAL (SELECT CASE WHEN pg_temp.h(b.m, 'cf') < 0.05
                                          THEN 1 + ((b.c * 7 + b.r) % :n_flash)::int
                                          ELSE :n_flash + 1 + ((b.c * 13 + b.r * 17) % :n_normal)::int END AS p,
                                     b.c AS c, b.qty AS qty) x
         ORDER BY x.c, x.p, b.m) y
  JOIN seed_prod sp ON sp.p = y.p;
COMMIT;
