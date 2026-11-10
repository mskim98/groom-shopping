-- 쿠폰 n_coupon 개: 앞 절반은 진행 중(is_active, issued_count NULL, 만료일 미래, 수량 = 발급 행 수 × 2 … 6),
-- 뒷 절반은 마감(is_active false, issued_count = 발급 행 수, 만료일 과거, 수량 = 발급 행 수)
-- 발급 행은 쿠폰마다 n_issue / n_coupon 개(나머지는 앞 쿠폰부터 1개씩)이고 사용자는 쿠폰별 난수 순서로 뽑아 (쿠폰, 사용자) 중복이 없다
SELECT COALESCE(max(id), 0) AS c_base FROM coupon
\gset
CREATE TEMP TABLE seed_coupon AS
SELECT k, (:n_issue / :n_coupon + CASE WHEN k <= :n_issue % :n_coupon THEN 1 ELSE 0 END)::bigint AS issue_cnt,
       k <= :n_coupon / 2 AS active
  FROM generate_series(1, :n_coupon) k;

BEGIN;
INSERT INTO coupon (id, name, description, quantity, amount, maximum_discount, minimum_cost, is_active, type,
                    expire_date, issued_count, created_at, updated_at)
SELECT :c_base + k, 'Seed Coupon ' || k, 'Seed coupon ' || k,
       CASE WHEN active THEN issue_cnt * (2 + k % 5) ELSE issue_cnt END,
       CASE k % 4 WHEN 1 THEN 10 WHEN 2 THEN 5000 WHEN 3 THEN 3000 ELSE 20 END,
       CASE k % 4 WHEN 1 THEN 50000 WHEN 0 THEN 15000 END,
       CASE k % 4 WHEN 0 THEN NULL ELSE 30000 END,
       active,
       (ARRAY['PERCENT', 'DISCOUNT', 'MIN_COST_AMOUNT', 'MAX_DISCOUNT_PERCENT'])[1 + (k % 4)],
       CASE WHEN active THEN (:'now_s'::timestamp)::date + (30 + k * 10) ELSE (:'now_s'::timestamp)::date - (5 + k) END,
       CASE WHEN active THEN NULL ELSE issue_cnt END,
       :'now_s'::timestamp - interval '60 days', :'now_s'::timestamp - interval '60 days'
  FROM seed_coupon;
COMMIT;

BEGIN;
INSERT INTO coupon_issue (id, is_active, created_at, deleted_at, user_id, coupon_id, request_id)
SELECT row_number() OVER (ORDER BY q.k, q.u), true,
       :'now_s'::timestamp - pg_temp.h(q.u * 100 + q.k, 'icreated') * interval '50 days', NULL, :u_base + q.u, :c_base + q.k, NULL
  FROM (SELECT c.k, u.u, c.issue_cnt,
               row_number() OVER (PARTITION BY c.k ORDER BY pg_temp.h(u.u, 'ci' || c.k)) AS rn
          FROM seed_coupon c CROSS JOIN generate_series(1, :n_users) u(u)) q
 WHERE q.rn <= q.issue_cnt;
COMMIT;
