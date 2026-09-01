-- coupon_id 단독 인덱스를 제거한다
--
-- V13 이 uq_coupon_issue_user (coupon_id, user_id) 를 추가하면서 이 인덱스가 남게 됐다
-- 복합 인덱스는 맨 앞 컬럼만으로도 쓸 수 있으므로 coupon_id 단독 조회를 그쪽이 이미 커버한다
--
-- 남겨 두면 발급 1건마다 인덱스를 4개 갱신한다 (pkey · user_id · uq · coupon_id)
-- 확정 경로가 INSERT 만 남으면 이 비용의 비중이 커진다 - 배치로 500건을 넣을 때
-- 인덱스 갱신이 2,000회에서 1,500회로 줄어든다
--
-- 실측(2026-09-01) - 크기 120kB · 스캔 5,814회
-- 같은 기간 uq_coupon_issue_user 는 596,814회 스캔됐다. 실제 조회는 그쪽이 받고 있었다

DROP INDEX IF EXISTS idx_coupon_issue_coupon_id;
