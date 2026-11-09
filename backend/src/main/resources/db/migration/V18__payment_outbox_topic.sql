-- payment_outbox 에 발행 토픽 컬럼을 추가한다. NULL 은 기존 결제 완료 이벤트(payment-events)다.
-- GIVEN_UP 보상의 DLQ 이벤트(payment-compensation-dlq)도 같은 트랜잭션의 Outbox 행으로 남기기 위해 필요하다
ALTER TABLE payment_outbox ADD COLUMN topic VARCHAR(100);
