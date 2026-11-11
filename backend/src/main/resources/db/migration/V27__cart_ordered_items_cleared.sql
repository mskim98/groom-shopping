-- 주문 하나가 장바구니에서 항목을 뺀 적이 있다는 표시. DELETE /v1/cart/ordered-items/{orderId} 가 같은 트랜잭션에서
-- 이 표에 INSERT ... ON CONFLICT DO NOTHING 으로 표시를 차지한 호출만 장바구니를 줄여, 같은 주문의 재호출 · 동시 호출이 두 번 빼지 않는다
CREATE TABLE cart_ordered_items_cleared (
    order_id   UUID        PRIMARY KEY,
    cleared_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
