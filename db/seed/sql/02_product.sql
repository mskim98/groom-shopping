-- 상품: 앞 n_flash 개는 플래시(고정 id f1a50000-0000-4000-8000-0000000000NN · 재고 1만 … 10만 · REDIS 모드),
-- 나머지는 일반(GENERAL 95 % · TICKET 5 %, 재고 0 … 5000, 약 2 % 가 재고 0 → OUT_OF_STOCK)
-- seed_prod 임시 표는 주문 항목이 상품을 고르는 번호(p)와 실제 id 의 대응이다
CREATE TEMP TABLE seed_prod (p int PRIMARY KEY, id uuid NOT NULL, name text NOT NULL, price int NOT NULL, flash boolean NOT NULL);
BEGIN;
INSERT INTO seed_prod
SELECT p,
       CASE WHEN p <= :n_flash THEN ('f1a50000-0000-4000-8000-' || lpad(p::text, 12, '0'))::uuid ELSE md5('seedprod:' || p)::uuid END,
       CASE WHEN p <= :n_flash THEN 'Flash Deal ' || p ELSE 'Seed Product ' || p || ' ' || (ARRAY['Laptop', 'Mouse', 'Keyboard', 'Monitor', 'Hub', 'Cable', 'Stand', 'Speaker'])[1 + (p % 8)] END,
       CASE WHEN p <= :n_flash THEN 10000 + (floor(pg_temp.h(p, 'fprice') * 90) * 1000)::int
            ELSE 1000 + (floor(pg_temp.h(p, 'nprice') * 299) * 100)::int END,
       p <= :n_flash
  FROM generate_series(1, :n_products) p;

INSERT INTO product (id, name, description, price, stock, is_active, category, status, threshold_value,
                     created_at, updated_at, flash_sale, stock_mode)
SELECT sp.id, sp.name, 'Seed product ' || sp.p, sp.price, s.stock, true,
       CASE WHEN NOT sp.flash AND pg_temp.h(sp.p, 'cat') < 0.05 THEN 'TICKET' ELSE 'GENERAL' END,
       CASE WHEN s.stock = 0 THEN 'OUT_OF_STOCK' ELSE 'AVAILABLE' END,
       10, t.ts, t.ts, sp.flash, 'REDIS'
  FROM seed_prod sp
  CROSS JOIN LATERAL (SELECT CASE WHEN sp.flash THEN 10000 + floor(pg_temp.h(sp.p, 'fstock') * 90001)::int
                                  WHEN pg_temp.h(sp.p, 'zero') < 0.02 THEN 0
                                  ELSE 1 + floor(pg_temp.h(sp.p, 'nstock') * 5000)::int END AS stock) s
  CROSS JOIN LATERAL (SELECT :'now_s'::timestamp - pg_temp.h(sp.p, 'pcreated') * interval '365 days' AS ts) t;
COMMIT;
