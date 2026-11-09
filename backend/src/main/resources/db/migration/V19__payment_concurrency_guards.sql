-- 동시 중복 확정 방어 (R115 T3-3)
-- 1) Payment 낙관적 락 버전. payment.version 은 Toss API 버전 문자열이라 다른 이름을 쓴다
ALTER TABLE payment ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

-- 2) payment_key 유일 인덱스. 엔티티 어노테이션(unique = true)만 있고 DDL 에는 없어 DB 가 중복을 막지 못했다
--    NULL(승인 전 결제)은 여러 행이어도 유일 위반이 아니다
CREATE UNIQUE INDEX uq_payment_payment_key ON payment (payment_key);

-- 3) 결제 한 건에 보상 행은 하나 - 같은 결제의 중복 보상 행이 두 번 취소를 시도하는 것을 막는다
ALTER TABLE payment_compensation ADD CONSTRAINT uq_payment_compensation_payment_id UNIQUE (payment_id);
