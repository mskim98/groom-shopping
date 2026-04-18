-- ============================================
-- V10: Payment Compensation Table
-- PG사 승인 후 내부 DB 실패 시 자동 환불 이력을 보관하고
-- 실패한 보상 트랜잭션을 @Scheduled 배치로 주기적으로 재시도하기 위한 테이블
-- ============================================

CREATE TABLE IF NOT EXISTS payment_compensation (
    id              UUID PRIMARY KEY,
    payment_id      UUID NOT NULL,
    payment_key     VARCHAR(200) NOT NULL,
    amount          INTEGER NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(30)  NOT NULL,        -- PENDING / SUCCEEDED / FAILED / GIVEN_UP
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    max_retry_count INTEGER      NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMP,
    last_error      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_compensation_payment FOREIGN KEY (payment_id) REFERENCES payment(id)
);

CREATE INDEX idx_payment_compensation_status_next_retry
    ON payment_compensation (status, next_retry_at);

CREATE INDEX idx_payment_compensation_payment_key
    ON payment_compensation (payment_key);
