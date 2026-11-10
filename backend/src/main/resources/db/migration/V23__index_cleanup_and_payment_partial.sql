-- 인덱스 정리: 유일 제약과 같은 열의 중복 인덱스 5개 삭제 + 결제 쪽 조회 2개를 부분 인덱스로 교체
-- 중복 인덱스는 읽기에 쓰이지 않으면서 INSERT/UPDATE 마다 갱신 비용만 든다

-- 1) 유일 제약이 이미 같은 열을 덮는 중복 인덱스
DROP INDEX IF EXISTS idx_users_email;              -- users_email_key (email)
DROP INDEX IF EXISTS idx_cart_user_id;             -- cart_user_id_key (user_id)
DROP INDEX IF EXISTS idx_payment_order_id;         -- payment_orderId_key (order_id)
DROP INDEX IF EXISTS idx_cart_item_cart_id;        -- uq_cart_item_unique (cart_id, product_id) 의 앞 열
DROP INDEX IF EXISTS idx_raffle_ticket_raffle_id;  -- uq_raffle_ticket_unique (raffle_id, ticket_number) 의 앞 열

-- 2) READY 대사: status = 'READY' AND updated_at < ? ORDER BY updated_at (60초 주기)
--    READY 행만 담는 부분 인덱스 - DONE 으로 바뀐 결제는 인덱스에서 빠진다
CREATE INDEX IF NOT EXISTS idx_payment_ready_updated ON payment (updated_at) WHERE status = 'READY';
DROP INDEX IF EXISTS idx_payment_status;

-- 3) Outbox 폴러: status = 'PENDING' ORDER BY created_at (0.2초 주기)
--    발행 끝난 행은 인덱스에 남지 않는다
CREATE INDEX IF NOT EXISTS idx_payment_outbox_pending ON payment_outbox (created_at) WHERE status = 'PENDING';
DROP INDEX IF EXISTS idx_payment_outbox_status_created;

-- 4) 상품 이름 검색: lower(name) LIKE '%검색어%' 는 앞에 % 가 붙어 일반 인덱스를 쓸 수 없다
--    3글자 조각(trigram) 인덱스 - 5만 건에서 읽은 블록 753 → 156 (2글자 이하 검색어는 여전히 전체 읽기)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_product_name_trgm ON product USING gin (lower(name) gin_trgm_ops);
