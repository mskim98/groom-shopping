-- 부하 유저 일괄 생성 (회차마다 돌릴 필요 없음. 인원이 모자랄 때만)
-- 실행: psql -v users=100000 -f backend/docs/measurements/runbook_seed_users.sql
--
-- k6 setup 의 signup 반복으로는 10만 명을 만들 수 없다
-- 회원가입은 요청마다 BCrypt 비용 10 을 태우므로 1건당 수십 ms 가 CPU 에 그대로 붙고,
-- 10만 건이면 회차 준비만 시간 단위가 된다. 라운드 15회 반복이라 그 비용이 총 시간을 결정한다
--
-- 그래서 해시를 미리 계산해 직접 넣는다. 비용을 4 로 낮춘 것은 로그인 검증 비용을 1/64 로
-- 줄이기 위한 것이고, 측정 대상은 발급 경로이지 로그인이 아니다
-- 이 계정들은 lt_ 접두사로 격리돼 있고 프로덕션 경로로 만들어지지 않는다
--
-- 해시 원문: loadtest123!  (k6 스크립트의 LT_PASSWORD 기본값과 같아야 한다)
-- 생성 명령: htpasswd -bnBC 4 x 'loadtest123!'
-- $2y$ 는 Spring Security BCryptPasswordEncoder 가 인식하는 리비전이다

\set ON_ERROR_STOP on

INSERT INTO users (email, password, name, role, grade, created_at, updated_at)
SELECT 'lt_' || i || '@test.com',
       '$2y$04$OqT3sj3au2/z0V9/PVGxfOAYEHPBiHoZ4dxGE3ZC3ry2pZb5U3lIu',
       'lt_user_' || i,
       'ROLE_USER',
       -- 컬럼 기본값은 NORMAL 이지만 Grade enum 에는 그 상수가 없다
       -- 그대로 넣으면 로그인이 500 으로 떨어진다 (No enum constant Grade.NORMAL)
       'BRONZE',
       NOW(),
       NOW()
  FROM generate_series(1, :users) AS i
    ON CONFLICT (email) DO NOTHING;

SELECT COUNT(*) AS load_users FROM users WHERE email LIKE 'lt\_%@test.com';
