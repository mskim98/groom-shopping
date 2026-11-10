-- 재고 원장: 승인 요청이 들어온 결제(DONE · CANCELED · READY)의 플래시 상품마다 SALE 한 행, CANCELED 결제는 반대 부호 REVERSAL 한 행을 더한다
-- 같은 주문에 같은 플래시 상품이 두 항목이면 유일 제약(payment_id, product_id, kind) 때문에 수량을 합쳐 한 행으로 쓴다
-- READY 결제의 SALE 만 applied = false (1초 배치가 아직 product.stock 에 접어 넣지 않은 행). 나머지는 이미 접힌 이력이고 product.stock 은 접힌 뒤의 값이다
BEGIN;
INSERT INTO stock_ledger (id, payment_id, product_id, qty, kind, applied, created_at, applied_at)
SELECT row_number() OVER (ORDER BY r.k, r.product_id, r.kind), r.payment_id, r.product_id, r.qty, r.kind, r.applied,
       r.created_at AT TIME ZONE 'Asia/Seoul',
       CASE WHEN r.applied THEN (r.created_at + interval '2 seconds') AT TIME ZONE 'Asia/Seoul' END
  FROM (SELECT o.k, md5('seedpay:' || o.k)::uuid AS payment_id, f.product_id, f.q AS qty, 'SALE' AS kind,
               o.pay_st <> 'READY' AS applied, o.created_at
          FROM seed_ord o
          JOIN (SELECT k, product_id, SUM(qty)::int AS q FROM seed_item WHERE p <= :n_flash GROUP BY k, product_id) f ON f.k = o.k
         WHERE o.pay_st IN ('DONE', 'CANCELED', 'READY')
        UNION ALL
        SELECT o.k, md5('seedpay:' || o.k)::uuid, f.product_id, -f.q, 'REVERSAL', true,
               LEAST(o.created_at + interval '5 minutes', :'now_s'::timestamp)
          FROM seed_ord o
          JOIN (SELECT k, product_id, SUM(qty)::int AS q FROM seed_item WHERE p <= :n_flash GROUP BY k, product_id) f ON f.k = o.k
         WHERE o.pay_st = 'CANCELED') r;
COMMIT;
