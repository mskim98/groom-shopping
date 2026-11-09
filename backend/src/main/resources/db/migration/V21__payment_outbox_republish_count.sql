-- READY 에 멈춘 결제 대사(PaymentReadyReconciler)가 이미 PUBLISHED 인 PaymentApproved 이벤트를 다시 내보낸 횟수
ALTER TABLE payment_outbox ADD COLUMN republish_count INT NOT NULL DEFAULT 0;

-- 대사가 결제 한 건의 outbox 행을 찾는 조회(aggregate_id)용. 기존 인덱스는 (status, created_at) 뿐이라 전체 스캔이 된다
CREATE INDEX idx_payment_outbox_aggregate ON payment_outbox (aggregate_id);
