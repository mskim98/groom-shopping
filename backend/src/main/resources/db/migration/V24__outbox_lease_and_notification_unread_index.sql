-- 1) Outbox 폴러 임대: 집은 행이 이 시각까지 다른 폴러에게 건너뛰어진다. NULL 은 임대 없음
ALTER TABLE payment_outbox ADD COLUMN claimed_until TIMESTAMP;

-- 2) Outbox 삭제 배치: status = 'PUBLISHED' AND published_at < ? ORDER BY published_at (10분 주기)
--    PENDING 행은 담지 않는다 - 폴러가 쓰는 idx_payment_outbox_pending 과 겹치지 않는다
CREATE INDEX idx_payment_outbox_published ON payment_outbox (published_at) WHERE status = 'PUBLISHED';

-- 3) 안 읽은 알림: user_id = ? AND is_read = false. 읽은 알림이 대부분이라 is_read 단독 인덱스는 선택도가 낮다
--    안 읽은 행만 담는 부분 인덱스로 바꾼다 - 읽음 처리된 알림은 인덱스에서 빠진다
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE is_read = false;
DROP INDEX idx_notifications_is_read;
