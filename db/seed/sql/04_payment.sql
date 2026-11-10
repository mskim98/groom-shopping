-- 결제(주문당 1건) · outbox · 보상
-- payment.id = md5('seedpay:'||k)::uuid, payment_key = seed_pk_<k> (DONE · CANCELED · READY 만 있다 — 승인 요청이 들어온 결제)
BEGIN;
INSERT INTO payment (id, order_id, user_id, payment_key, transaction_id, last_transaction_key, amount, balance_amount,
                     supplied_amount, vat_amount, tax_free_amount, tax_exemption_amount, status, method, order_name,
                     customer_name, m_id, version, type, currency, failure_code, failure_message,
                     requested_at, approved_at, canceled_at, created_at, updated_at)
SELECT md5('seedpay:' || o.k)::uuid, o.id, o.user_id,
       CASE WHEN o.pay_st IN ('DONE', 'CANCELED', 'READY') THEN 'seed_pk_' || o.k END,
       CASE WHEN o.pay_st IN ('DONE', 'CANCELED') THEN 'seed_tx_' || o.k END,
       CASE WHEN o.pay_st IN ('DONE', 'CANCELED') THEN 'seed_lt_' || o.k END,
       o.sub_total,
       CASE o.pay_st WHEN 'DONE' THEN o.sub_total WHEN 'CANCELED' THEN 0 END,
       round(o.sub_total / 1.1)::int, o.sub_total - round(o.sub_total / 1.1)::int, 0, 0,
       o.pay_st,
       CASE WHEN o.pay_st IN ('DONE', 'CANCELED', 'READY')
            THEN CASE WHEN pg_temp.h(o.k, 'meth') < 0.8 THEN 'CARD' WHEN pg_temp.h(o.k, 'meth') < 0.95 THEN 'EASY_PAY' ELSE 'TRANSFER' END END,
       'Seed order ' || o.k, 'Seed User', 'seedmid', '2022-11-16', 'NORMAL', 'KRW',
       CASE o.pay_st WHEN 'FAILED' THEN 'PAY_PROCESS_CANCELED' WHEN 'ABORTED' THEN 'PAY_PROCESS_ABORTED' END,
       CASE o.pay_st WHEN 'FAILED' THEN 'seed: 결제 실패' WHEN 'ABORTED' THEN 'seed: 결제 중단' END,
       CASE WHEN o.pay_st IN ('DONE', 'CANCELED', 'READY') THEN o.created_at + interval '1 second' END,
       CASE WHEN o.pay_st IN ('DONE', 'CANCELED') THEN o.created_at + interval '3 seconds' END,
       CASE WHEN o.pay_st = 'CANCELED' THEN LEAST(o.created_at + interval '5 minutes', :'now_s'::timestamp) END,
       o.created_at,
       -- READY 의 updated_at 은 최근 10 분에 퍼진다(READY 대사 · 인덱스 idx_payment_ready_updated 가 보는 값)
       CASE WHEN o.pay_st = 'READY' THEN :'now_s'::timestamp - pg_temp.h(o.k, 'rupd') * interval '10 minutes'
            ELSE o.created_at + interval '5 seconds' END
  FROM seed_ord o;
COMMIT;

-- outbox: 승인 요청이 들어온 결제(DONE · CANCELED · READY)마다 PaymentApproved 이벤트 한 건
-- DONE · CANCELED 는 PUBLISHED(published_at 은 승인 직후 = 최근 30 일), READY 는 PENDING
-- payload 는 PaymentApprovedEvent 의 JSON 모양(paymentId · orderId · amount · tossResponse · reservedItems[productId, quantity, mode])
BEGIN;
INSERT INTO payment_outbox (id, aggregate_id, event_type, payload, status, created_at, published_at, republish_count)
SELECT md5('seedobx:' || o.k)::uuid, md5('seedpay:' || o.k)::uuid, 'PaymentApproved',
       jsonb_build_object(
         'paymentId', md5('seedpay:' || o.k)::uuid,
         'orderId', o.id,
         'amount', o.sub_total,
         'tossResponse', jsonb_build_object(
           'version', '2022-11-16', 'paymentKey', 'seed_pk_' || o.k, 'orderId', o.id::text,
           'orderName', 'Seed order ' || o.k, 'method', '카드', 'status', 'DONE', 'type', 'NORMAL', 'currency', 'KRW',
           'totalAmount', o.sub_total, 'balanceAmount', o.sub_total,
           'suppliedAmount', round(o.sub_total / 1.1)::int, 'vat', o.sub_total - round(o.sub_total / 1.1)::int,
           'taxFreeAmount', 0, 'taxExemptionAmount', 0,
           'requestedAt', to_char(o.created_at + interval '1 second', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
           'approvedAt', to_char(o.created_at + interval '3 seconds', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00'),
         'reservedItems', COALESCE((SELECT jsonb_agg(jsonb_build_object('productId', f.product_id, 'quantity', f.q, 'mode', 'REDIS') ORDER BY f.product_id)
                                      FROM (SELECT product_id, SUM(qty)::int AS q FROM seed_item WHERE k = o.k AND p <= :n_flash GROUP BY product_id) f),
                                   '[]'::jsonb))::text,
       CASE WHEN o.pay_st = 'READY' THEN 'PENDING' ELSE 'PUBLISHED' END,
       CASE WHEN o.pay_st = 'READY' THEN :'now_s'::timestamp - pg_temp.h(o.k, 'rupd') * interval '10 minutes'
            ELSE LEAST(o.created_at + interval '3 seconds', :'now_s'::timestamp) END,
       CASE WHEN o.pay_st = 'READY' THEN NULL
            ELSE LEAST(o.created_at + interval '4 seconds', :'now_s'::timestamp) END,
       0
  FROM seed_ord o
 WHERE o.pay_st IN ('DONE', 'CANCELED', 'READY');
COMMIT;

-- 보상: 환불이 필요했을 법한 결제(CANCELED · FAILED · ABORTED)에서 n_comp 건
-- 앞에서부터 PENDING n_comp_pending · GIVEN_UP n_comp_each · 나머지 5 상태 각 n_comp_each, 남은 행은 SUCCEEDED
BEGIN;
INSERT INTO payment_compensation (id, payment_id, payment_key, amount, reason, status, retry_count, max_retry_count,
                                  next_retry_at, last_error, created_at, updated_at)
SELECT md5('seedcmp:' || c.k)::uuid, md5('seedpay:' || c.k)::uuid, 'seed_pk_' || c.k, c.sub_total,
       'seed: 승인 뒤 DB 후처리 실패 환불', c.status,
       CASE c.status WHEN 'SUCCEEDED' THEN 1 WHEN 'GIVEN_UP' THEN 5 WHEN 'PENDING' THEN 0 ELSE 2 END, 5,
       CASE WHEN c.status = 'PENDING' THEN :'now_s'::timestamp + pg_temp.h(c.k, 'retry') * interval '1 hour' END,
       CASE WHEN c.status IN ('FAILED', 'GIVEN_UP', 'RECONCILE_NEEDED') THEN 'seed: Toss 취소 호출 실패' END,
       c.created_at, c.created_at + interval '1 minute'
  FROM (SELECT x.k, x.sub_total, x.created_at,
               CASE WHEN x.rn <= :n_comp_pending THEN 'PENDING'
                    WHEN x.rn <= :n_comp_pending + :n_comp_each THEN 'GIVEN_UP'
                    WHEN x.rn <= :n_comp_pending + 2 * :n_comp_each THEN 'PROCESSING'
                    WHEN x.rn <= :n_comp_pending + 3 * :n_comp_each THEN 'FAILED'
                    WHEN x.rn <= :n_comp_pending + 4 * :n_comp_each THEN 'NOT_APPROVED'
                    WHEN x.rn <= :n_comp_pending + 5 * :n_comp_each THEN 'RECONCILE_NEEDED'
                    WHEN x.rn <= :n_comp_pending + 6 * :n_comp_each THEN 'SUPERSEDED'
                    ELSE 'SUCCEEDED' END AS status
          FROM (SELECT o.k, o.sub_total, o.created_at,
                       row_number() OVER (ORDER BY pg_temp.h(o.k, 'cmp')) AS rn
                  FROM seed_ord o WHERE o.pay_st IN ('CANCELED', 'FAILED', 'ABORTED')) x
         WHERE x.rn <= :n_comp) c;
COMMIT;
