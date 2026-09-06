-- payment_compensation 에 PROCESSING 상태를 추가한다(다중 배치 인스턴스의 선점 표시).
-- status 컬럼은 VARCHAR 라 CHECK 제약이 없고 값 자체는 애플리케이션(CompensationStatus)이 관리한다.
COMMENT ON COLUMN payment_compensation.status IS 'PENDING / PROCESSING / SUCCEEDED / FAILED / GIVEN_UP';

-- 선점 조회의 회수 조건(status = PROCESSING AND updated_at <= staleThreshold)이 이 인덱스를 탄다.
-- 기존 idx_payment_compensation_status_next_retry 는 next_retry_at 기준이라 이 조건에 맞지 않는다
CREATE INDEX idx_payment_compensation_status_updated_at
    ON payment_compensation (status, updated_at);
