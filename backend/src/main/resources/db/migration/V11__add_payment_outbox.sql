-- 결제 Outbox 테이블
-- 결제 후처리 트랜잭션과 '같은 커밋'으로 이벤트를 적재(at-least-once)하고,
-- 별도 publisher 가 Kafka 로 발행한다. DB 커밋이 곧 이벤트 영구 기록이 된다.
CREATE TABLE payment_outbox (
    id            UUID         PRIMARY KEY,
    aggregate_id  UUID         NOT NULL,                    -- 결제(Payment) ID
    event_type    VARCHAR(100) NOT NULL,                    -- 예: PAYMENT_COMPLETED
    payload       TEXT         NOT NULL,                    -- 이벤트 JSON
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    published_at  TIMESTAMP
);

-- publisher 가 PENDING 행을 오래된 순으로 빠르게 스캔하기 위한 인덱스
CREATE INDEX idx_payment_outbox_status_created ON payment_outbox (status, created_at);
