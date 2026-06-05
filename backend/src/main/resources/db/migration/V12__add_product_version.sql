-- 상품 재고 차감의 Lost Update 차단용 낙관적 락 버전 컬럼.
-- UPDATE 시 'WHERE id=? AND version=?' 가 자동 부착되어, 동시 차감 중 한쪽이 충돌(영향 행 0)하면
-- OptimisticLockException 이 발생한다. 기존 행은 0으로 초기화.
ALTER TABLE product ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
