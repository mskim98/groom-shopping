-- 사용자: seed_<k>@test.com · 비밀번호 loadtest123! · ROLE_USER · 등급 5종 균등
-- 해시는 BCrypt cost 4 — 로그인 응답 시간이 해시 비용에 묻히지 않게 낮췄다(boot-smoke.sh 가 Spring 이 받아들이는지 확인한다)
\set pw '$2y$04$OqT3sj3au2/z0V9/PVGxfOAYEHPBiHoZ4dxGE3ZC3ry2pZb5U3lIu'
SELECT COALESCE(max(id), 0) AS u_base FROM users
\gset
BEGIN;
INSERT INTO users (id, email, password, name, role, grade, created_at, updated_at)
SELECT :u_base + k, 'seed_' || k || '@test.com', :'pw', 'Seed User ' || k, 'ROLE_USER',
       (ARRAY['BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'VIP'])[1 + (k % 5)], t.ts, t.ts
  FROM generate_series(1, :n_users) k
  CROSS JOIN LATERAL (SELECT :'now_s'::timestamp - pg_temp.h(k, 'ucreated') * interval '365 days' AS ts) t;
COMMIT;
