-- 마감 확정 발급 수를 담는 컬럼을 더한다
--
-- S2 에서 quantity 의 뜻이 바뀐다. 지금까지는 "남은 수량"이라 확정마다 감산했지만
-- 이제는 아무도 고치지 않는 "발행 수량"이다. 확정 경로에서 coupon 테이블을 아예 쓰지 않으므로
-- 감산할 주체 자체가 없어진다
--
-- 그러면 "몇 장 나갔나"를 셀 곳이 필요한데, 이벤트 진행 중에는 COUNT(coupon_issue) 가 답이다
-- 카운터 컬럼을 따로 두고 매번 갱신하면 그 컬럼과 행 수가 어긋날 여지가 생기고,
-- 어긋났을 때 어느 쪽이 진실인지 정해야 한다. 행만 두면 그 문제가 생기지 않는다
--
-- NULL = 진행 중 (COUNT 로 계산해서 봐라)
-- 값 있음 = 마감 확정. 이후로는 변하지 않으므로 리포트가 매번 셀 이유가 없다
-- issued_count > quantity 면 그 자체가 초과 발급의 증거로 남는다
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS issued_count BIGINT;

COMMENT ON COLUMN coupon.issued_count IS '마감 시 1회 굳힌 확정 발급 수. NULL 이면 진행 중';
COMMENT ON COLUMN coupon.quantity IS 'S2 이후 불변 발행 수량. 확정 경로가 감산하지 않는다';
