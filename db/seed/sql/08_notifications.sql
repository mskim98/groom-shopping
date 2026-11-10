-- 알림: n_notif 개, 열 번째마다 미읽음(10 %). 사용자와 상품은 난수로 고른다
BEGIN;
INSERT INTO notifications (id, current_stock, threshold_value, message, is_read, created_at, user_id, product_id)
SELECT m, sp.stock_now, 10, 'Seed notification ' || m || ': ' || sp.name || ' 재고가 임계치 이하입니다', (m % 10 <> 0),
       :'now_s'::timestamp - pg_temp.h(m, 'ncreated') * interval '30 days',
       :u_base + 1 + floor(pg_temp.h(m, 'nu') * :n_users)::int, sp.id
  FROM generate_series(1, :n_notif) m
  CROSS JOIN LATERAL (SELECT s.id, s.name, floor(pg_temp.h(m, 'ns') * 10)::int AS stock_now
                        FROM seed_prod s WHERE s.p = 1 + floor(pg_temp.h(m, 'np') * :n_products)::int) sp;
COMMIT;
