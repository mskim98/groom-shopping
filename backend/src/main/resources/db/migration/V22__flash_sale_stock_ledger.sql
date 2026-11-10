-- 플래시 세일 재고 원장. 판매는 이 표에 INSERT 로만 기록하고, 1초 배치(StockLedgerApplyJob)가 product.stock 에 한꺼번에 접어 넣는다
-- 남은 재고 = product.stock - SUM(qty) (applied = false 인 행) 이고, 배치가 두 변경을 한 트랜잭션으로 커밋하므로 어느 순간에도 성립한다
CREATE TABLE stock_ledger (
    id         BIGSERIAL PRIMARY KEY,
    payment_id UUID        NOT NULL,
    product_id UUID        NOT NULL,
    qty        INT         NOT NULL,                  -- SALE 은 양수, REVERSAL 은 음수
    kind       VARCHAR(16) NOT NULL,
    applied    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ,
    CONSTRAINT ck_stock_ledger_kind CHECK (kind IN ('SALE', 'REVERSAL')),
    -- 같은 결제의 같은 상품에 SALE 과 REVERSAL 이 각각 한 번만 남는다 - 실패 경로가 두 번 돌아도 행이 늘지 않는다
    CONSTRAINT uq_stock_ledger_payment_product_kind UNIQUE (payment_id, product_id, kind)
);

-- 배치와 전환 트랜잭션이 읽는 것은 미적용 행뿐이라 부분 인덱스로 둔다(적용된 행은 계속 쌓인다)
CREATE INDEX idx_stock_ledger_unapplied ON stock_ledger (product_id) WHERE applied = false;

-- flash_sale 인 상품만 Redis 선점 + 원장 경로를 쓴다. stock_mode 는 그 상품의 재고 판정이 Redis 인지 DB 인지 나타낸다
ALTER TABLE product ADD COLUMN flash_sale BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN stock_mode VARCHAR(8) NOT NULL DEFAULT 'REDIS';
ALTER TABLE product ADD COLUMN stock_mode_changed_at TIMESTAMPTZ;

-- 마지막 DB 방어선. 적용 배치 · 전환 · REVERSAL 에 결함이 있어도 음수 재고를 조용히 쓰지 않고 그 트랜잭션이 실패한다(원장 행은 미적용으로 남아 보이고 재시도된다)
ALTER TABLE product ADD CONSTRAINT ck_product_stock_non_negative CHECK (stock >= 0);
