-- verify.sh 가 00_sizes.sql 뒤에 이어 붙여 한 psql 세션으로 실행한다. 읽기만 한다(임시 표 chk · 임시 뷰뿐)
-- 검사 묶음마다 chk 에 한 행(이름 · 통과 여부 · 설명)을 넣고, 끝에 'PASS 이름: 설명' / 'FAIL 이름: 설명' 으로 찍는다
-- 마이그레이션이 직접 넣는 행(사용자 22 · 상품 40 · 쿠폰 6 · 장바구니 22 · 장바구니 항목 5)은 목표 행 수에서 뺀다
-- tol_scale: 허용 오차 규칙을 고르는 배수. 기본은 scale 이고 SEED_VERIFY_TOL_SCALE 로 바꾼다(작은 DB 에서 scale 1 규칙을 시험하려는 용도)
\if :{?tol_scale}
\else
\set tol_scale :scale
\endif
CREATE TEMP TABLE chk (ord serial, name text, ok boolean, detail text);

-- 승인 요청이 들어온 결제(DONE · CANCELED · READY)의 플래시 상품 쌍 — 재고 원장 SALE 행의 기대 개수, CANCELED 만 REVERSAL 의 기대 개수
CREATE TEMP VIEW flash_pairs AS
SELECT p.id AS payment_id, p.status, oi.product_id
  FROM order_item oi
  JOIN payment p ON p.order_id = oi.order_id
  JOIN product pr ON pr.id = oi.product_id AND pr.flash_sale
 WHERE p.status IN ('DONE', 'CANCELED', 'READY')
 GROUP BY p.id, p.status, oi.product_id;

-- 1. counts: 허용 오차는 scale >= 1 이면 목표 300 이상 ceil(1 %) · 300 미만 0(정확히 같음), scale < 1 이면 max(1 %, 3행) payment_outbox 목표는 승인 요청이 들어온 결제의 수다
--    재고 원장의 SALE · REVERSAL 은 flash_pairs 와 정확히 같아야 한다
INSERT INTO chk (name, ok, detail)
SELECT 'counts',
       bool_and(abs(actual - target) <= CASE WHEN :tol_scale::numeric >= 1
                                             THEN (CASE WHEN target >= 300 THEN ceil(0.01 * target) ELSE 0 END)
                                             ELSE GREATEST(0.01 * target, 3) END),
       string_agg(t || '=' || actual || '/' || target, ' ' ORDER BY t)
  FROM (VALUES ('users', (SELECT count(*) - 22 FROM users), :n_users::numeric),
               ('product', (SELECT count(*) - 40 FROM product), :n_products::numeric),
               ('flash', (SELECT count(*) FROM product WHERE flash_sale), :n_flash::numeric),
               ('orders', (SELECT count(*) FROM orders), :n_orders::numeric),
               ('order_item', (SELECT count(*) FROM order_item), :n_items::numeric),
               ('payment', (SELECT count(*) FROM payment), :n_orders::numeric),
               ('payment_outbox', (SELECT count(*) FROM payment_outbox), (SELECT count(*) FROM payment WHERE status IN ('DONE', 'CANCELED', 'READY'))::numeric),
               ('payment_compensation', (SELECT count(*) FROM payment_compensation), :n_comp::numeric),
               ('coupon', (SELECT count(*) - 6 FROM coupon), :n_coupon::numeric),
               ('coupon_issue', (SELECT count(*) FROM coupon_issue), :n_issue::numeric),
               ('cart', (SELECT count(*) - 22 FROM cart), :n_users::numeric),
               ('cart_item', (SELECT count(*) - 5 FROM cart_item), :n_cartitem::numeric),
               ('notifications', (SELECT count(*) FROM notifications), :n_notif::numeric),
               ('stock_ledger_sale', (SELECT count(*) FROM stock_ledger WHERE kind = 'SALE'), (SELECT count(*) FROM flash_pairs)::numeric),
               ('stock_ledger_reversal', (SELECT count(*) FROM stock_ledger WHERE kind = 'REVERSAL'), (SELECT count(*) FROM flash_pairs WHERE status = 'CANCELED')::numeric)
       ) v(t, actual, target);
-- 원장 행 수는 허용 오차 없이 같아야 한다
UPDATE chk SET ok = ok AND (SELECT count(*) FROM stock_ledger WHERE kind = 'SALE') = (SELECT count(*) FROM flash_pairs)
                       AND (SELECT count(*) FROM stock_ledger WHERE kind = 'REVERSAL') = (SELECT count(*) FROM flash_pairs WHERE status = 'CANCELED')
 WHERE name = 'counts';

-- 2. flyway: 성공한 기록 22 줄
INSERT INTO chk (name, ok, detail)
SELECT 'flyway', count(*) FILTER (WHERE success) = 22 AND count(*) = 22,
       'rows=' || count(*) || ' successful=' || count(*) FILTER (WHERE success)
  FROM flyway_schema_history;

-- 3. refs: 참조가 가리키는 행이 없는 고아 행 수가 모두 0
INSERT INTO chk (name, ok, detail)
SELECT 'refs', COALESCE(bool_and(n = 0), false),
       'orphans: ' || string_agg(t || '=' || n, ' ' ORDER BY t)
  FROM (
    SELECT 'orders.user_id' AS t, count(*) AS n FROM orders x WHERE NOT EXISTS (SELECT 1 FROM users r WHERE r.id = x.user_id)
    UNION ALL SELECT 'order_item.order_id', count(*) FROM order_item x WHERE NOT EXISTS (SELECT 1 FROM orders r WHERE r.id = x.order_id)
    UNION ALL SELECT 'order_item.product_id', count(*) FROM order_item x WHERE NOT EXISTS (SELECT 1 FROM product r WHERE r.id = x.product_id)
    UNION ALL SELECT 'payment.order_id', count(*) FROM payment x WHERE NOT EXISTS (SELECT 1 FROM orders r WHERE r.id = x.order_id)
    UNION ALL SELECT 'payment.user_id', count(*) FROM payment x WHERE NOT EXISTS (SELECT 1 FROM users r WHERE r.id = x.user_id)
    UNION ALL SELECT 'payment_outbox.aggregate_id', count(*) FROM payment_outbox x WHERE NOT EXISTS (SELECT 1 FROM payment r WHERE r.id = x.aggregate_id)
    UNION ALL SELECT 'payment_compensation.payment_id', count(*) FROM payment_compensation x WHERE NOT EXISTS (SELECT 1 FROM payment r WHERE r.id = x.payment_id)
    UNION ALL SELECT 'stock_ledger.payment_id', count(*) FROM stock_ledger x WHERE NOT EXISTS (SELECT 1 FROM payment r WHERE r.id = x.payment_id)
    UNION ALL SELECT 'stock_ledger.product_id', count(*) FROM stock_ledger x WHERE NOT EXISTS (SELECT 1 FROM product r WHERE r.id = x.product_id)
    UNION ALL SELECT 'coupon_issue.user_id', count(*) FROM coupon_issue x WHERE NOT EXISTS (SELECT 1 FROM users r WHERE r.id = x.user_id)
    UNION ALL SELECT 'coupon_issue.coupon_id', count(*) FROM coupon_issue x WHERE NOT EXISTS (SELECT 1 FROM coupon r WHERE r.id = x.coupon_id)
    UNION ALL SELECT 'cart.user_id', count(*) FROM cart x WHERE NOT EXISTS (SELECT 1 FROM users r WHERE r.id = x.user_id)
    UNION ALL SELECT 'cart_item.cart_id', count(*) FROM cart_item x WHERE NOT EXISTS (SELECT 1 FROM cart r WHERE r.id = x.cart_id)
    UNION ALL SELECT 'cart_item.product_id', count(*) FROM cart_item x WHERE NOT EXISTS (SELECT 1 FROM product r WHERE r.id = x.product_id)
    UNION ALL SELECT 'notifications.user_id', count(*) FROM notifications x WHERE NOT EXISTS (SELECT 1 FROM users r WHERE r.id = x.user_id)
    UNION ALL SELECT 'notifications.product_id', count(*) FROM notifications x WHERE NOT EXISTS (SELECT 1 FROM product r WHERE r.id = x.product_id)
  ) o;

-- 4. enums: 열마다 자바 enum 이름이 아닌 값을 가진 행 수가 0 (NULL 은 허용되는 열만 건너뛴다)
--    users.role Role · users.grade Grade · product.category ProductCategory · product.status ProductStatus · product.stock_mode StockMode
--    orders.status OrderStatus · payment.status PaymentStatus · payment.method PaymentMethod · payment_compensation.status CompensationStatus
--    payment_outbox.status PENDING/PUBLISHED · coupon.type CouponType · stock_ledger.kind SALE/REVERSAL
INSERT INTO chk (name, ok, detail)
SELECT 'enums', bool_and(n = 0), 'bad values: ' || string_agg(t || '=' || n, ' ' ORDER BY t)
  FROM (
    SELECT 'users.role' AS t, count(*) AS n FROM users WHERE role IS NULL OR role NOT IN ('ROLE_USER', 'ROLE_ADMIN')
    UNION ALL SELECT 'users.grade', count(*) FROM users WHERE grade IS NULL OR grade NOT IN ('BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'VIP')
    UNION ALL SELECT 'product.category', count(*) FROM product WHERE category IS NULL OR category NOT IN ('GENERAL', 'TICKET', 'RAFFLE')
    UNION ALL SELECT 'product.status', count(*) FROM product WHERE status IS NULL OR status NOT IN ('AVAILABLE', 'OUT_OF_STOCK')
    UNION ALL SELECT 'product.stock_mode', count(*) FROM product WHERE stock_mode IS NULL OR stock_mode NOT IN ('REDIS', 'DB')
    UNION ALL SELECT 'orders.status', count(*) FROM orders WHERE status IS NULL OR status NOT IN ('PENDING', 'CONFIRMED', 'PREPARING', 'SHIPPING', 'DELIVERED', 'COMPLETED', 'CANCELLED')
    UNION ALL SELECT 'payment.status', count(*) FROM payment WHERE status IS NULL OR status NOT IN ('PENDING', 'READY', 'IN_PROGRESS', 'WAITING_FOR_DEPOSIT', 'DONE', 'CANCELED', 'PARTIAL_CANCELED', 'ABORTED', 'EXPIRED', 'FAILED')
    UNION ALL SELECT 'payment.method', count(*) FROM payment WHERE method IS NOT NULL AND method NOT IN ('CARD', 'VIRTUAL_ACCOUNT', 'TRANSFER', 'MOBILE', 'CULTURE_GIFT_CERTIFICATE', 'BOOK_GIFT_CERTIFICATE', 'GAME_GIFT_CERTIFICATE', 'EASY_PAY')
    UNION ALL SELECT 'payment_compensation.status', count(*) FROM payment_compensation WHERE status IS NULL OR status NOT IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'GIVEN_UP', 'NOT_APPROVED', 'RECONCILE_NEEDED', 'SUPERSEDED')
    UNION ALL SELECT 'payment_outbox.status', count(*) FROM payment_outbox WHERE status IS NULL OR status NOT IN ('PENDING', 'PUBLISHED')
    UNION ALL SELECT 'payment_outbox.event_type', count(*) FROM payment_outbox WHERE event_type IS NULL OR event_type <> 'PaymentApproved'
    UNION ALL SELECT 'coupon.type', count(*) FROM coupon WHERE type IS NULL OR type NOT IN ('PERCENT', 'DISCOUNT', 'MIN_COST_AMOUNT', 'MAX_DISCOUNT_PERCENT')
    UNION ALL SELECT 'stock_ledger.kind', count(*) FROM stock_ledger WHERE kind IS NULL OR kind NOT IN ('SALE', 'REVERSAL')
  ) e;

-- 5. amounts: orders.sub_total = SUM(order_item.sub_total) 이고 payment.amount = orders.total_amount 인 어긋난 행 수가 0
INSERT INTO chk (name, ok, detail)
SELECT 'amounts', a.n = 0 AND b.n = 0, 'orders vs items mismatches=' || a.n || ' payment vs order mismatches=' || b.n
  FROM (SELECT count(*) AS n FROM orders o
         WHERE o.sub_total IS DISTINCT FROM (SELECT SUM(i.sub_total) FROM order_item i WHERE i.order_id = o.id)) a,
       (SELECT count(*) AS n FROM payment p JOIN orders o ON o.id = p.order_id
         WHERE p.amount IS DISTINCT FROM o.total_amount) b;

-- 6. flash-stock: 플래시 상품마다 stock − 미적용 qty 합 ≥ 0
INSERT INTO chk (name, ok, detail)
SELECT 'flash-stock', count(*) FILTER (WHERE remaining < 0) = 0,
       'flash products=' || count(*) || ' negative=' || count(*) FILTER (WHERE remaining < 0)
  FROM (SELECT p.stock - COALESCE((SELECT SUM(l.qty) FROM stock_ledger l WHERE l.product_id = p.id AND l.applied = false), 0) AS remaining
          FROM product p WHERE p.flash_sale) f;

-- 7. coupon-quota: 쿠폰마다 발급 행 수 ≤ quantity
INSERT INTO chk (name, ok, detail)
SELECT 'coupon-quota', count(*) FILTER (WHERE issued > quantity) = 0,
       'coupons=' || count(*) || ' over quota=' || count(*) FILTER (WHERE issued > quantity)
  FROM (SELECT c.quantity, (SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id) AS issued FROM coupon c) q;

-- 8. sequences: 시퀀스의 다음 값이 그 표의 최대 id 보다 크다 (BIGSERIAL 표 8개)
CREATE FUNCTION pg_temp.next_of(tbl text) RETURNS bigint LANGUAGE sql AS
$$ SELECT COALESCE(last_value, 0) + 1 FROM pg_sequences
    WHERE schemaname = 'public' AND sequencename = (parse_ident(pg_get_serial_sequence(tbl, 'id')))[2] $$;
INSERT INTO chk (name, ok, detail)
SELECT 'sequences', bool_and(nxt > max_id), string_agg(t || ' next=' || nxt || ' max=' || max_id, ' ' ORDER BY t)
  FROM (
    SELECT 'users' AS t, (SELECT max(id) FROM users) AS max_id, pg_temp.next_of('users') AS nxt
    UNION ALL SELECT 'cart', (SELECT max(id) FROM cart), pg_temp.next_of('cart')
    UNION ALL SELECT 'cart_item', (SELECT max(id) FROM cart_item), pg_temp.next_of('cart_item')
    UNION ALL SELECT 'order_item', (SELECT max(id) FROM order_item), pg_temp.next_of('order_item')
    UNION ALL SELECT 'coupon', (SELECT max(id) FROM coupon), pg_temp.next_of('coupon')
    UNION ALL SELECT 'coupon_issue', (SELECT max(id) FROM coupon_issue), pg_temp.next_of('coupon_issue')
    UNION ALL SELECT 'stock_ledger', (SELECT max(id) FROM stock_ledger), pg_temp.next_of('stock_ledger')
    UNION ALL SELECT 'notifications', (SELECT max(id) FROM notifications), pg_temp.next_of('notifications')
  ) s;

-- 9. minority: 소수 상태가 남아 있다 — READY = n_ready · outbox PENDING = READY 수 · 미적용 원장 행 · 보상 8 상태 전부
INSERT INTO chk (name, ok, detail)
SELECT 'minority',
       ready = :n_ready AND pending >= 1 AND pending = ready AND unapplied >= 1 AND comp_pending >= 1 AND comp_states = 8,
       'payment READY=' || ready || ' outbox PENDING=' || pending || ' unapplied ledger=' || unapplied
         || ' compensation PENDING=' || comp_pending || ' compensation states=' || comp_states
  FROM (SELECT (SELECT count(*) FROM payment WHERE status = 'READY') AS ready,
               (SELECT count(*) FROM payment_outbox WHERE status = 'PENDING') AS pending,
               (SELECT count(*) FROM stock_ledger WHERE applied = false) AS unapplied,
               (SELECT count(*) FROM payment_compensation WHERE status = 'PENDING') AS comp_pending,
               (SELECT count(DISTINCT status) FROM payment_compensation) AS comp_states) m;

-- 10. skew: 상위 1 % 상품이 order_item 의 30 % 이상, 상위 10 % 사용자가 주문의 50 % 이상
INSERT INTO chk (name, ok, detail)
SELECT 'skew', prod >= 0.30 AND usr >= 0.50, 'top 1% products carry ' || round(prod * 100, 1) || '% of order_item, top 10% users carry ' || round(usr * 100, 1) || '% of orders'
  FROM (SELECT (SELECT COALESCE(SUM(n), 0) FROM (SELECT count(*) AS n FROM order_item GROUP BY product_id ORDER BY 1 DESC
                                                  LIMIT (SELECT ceil(count(*) * 0.01) FROM product)) t)::numeric
                / GREATEST((SELECT count(*) FROM order_item), 1) AS prod,
               (SELECT COALESCE(SUM(n), 0) FROM (SELECT count(*) AS n FROM orders GROUP BY user_id ORDER BY 1 DESC
                                                  LIMIT (SELECT ceil(count(*) * 0.1) FROM users)) t)::numeric
                / GREATEST((SELECT count(*) FROM orders), 1) AS usr) s;

SELECT CASE WHEN ok THEN 'PASS ' ELSE 'FAIL ' END || name || ': ' || detail FROM chk ORDER BY ord;
