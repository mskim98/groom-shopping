-- 명시한 id 로 넣은 BIGSERIAL 표의 시퀀스를 최대 id 로 맞추고(다음 값 = 최대 + 1), 통계를 갱신한다
SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT max(id) FROM users));
SELECT setval(pg_get_serial_sequence('cart', 'id'), (SELECT max(id) FROM cart));
SELECT setval(pg_get_serial_sequence('cart_item', 'id'), (SELECT max(id) FROM cart_item));
SELECT setval(pg_get_serial_sequence('order_item', 'id'), (SELECT max(id) FROM order_item));
SELECT setval(pg_get_serial_sequence('coupon', 'id'), (SELECT max(id) FROM coupon));
SELECT setval(pg_get_serial_sequence('coupon_issue', 'id'), (SELECT max(id) FROM coupon_issue));
SELECT setval(pg_get_serial_sequence('stock_ledger', 'id'), (SELECT max(id) FROM stock_ledger));
SELECT setval(pg_get_serial_sequence('notifications', 'id'), (SELECT max(id) FROM notifications));
ANALYZE;
