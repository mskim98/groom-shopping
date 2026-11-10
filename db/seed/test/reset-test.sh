#!/usr/bin/env bash
# T2-3 시험: 템플릿 shop_seed_t2a(scale 0.01)에서 shop_seed_t2b 를 reset.sh 로 만들고, 행을 지우고 고친 뒤
# 다시 reset 해서 표별 행 수와 고정 조회의 md5 가 템플릿 값으로 돌아오는지, 거절 대상은 exit 2 이고 아무것도 안 지워지는지 본다
# 환경 변수: SEED_PG_CONTAINER(dev-db) SEED_REDIS_PORT SEED_KAFKA_CONTAINER — 지우는 DB 는 shop_seed_t2b 뿐(템플릿이 없으면 shop_seed_t2a 를 만든다)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
. "$SEED/lib.sh"

TPL=shop_seed_t2a
TGT=shop_seed_t2b
FAILS=0
SLEEPER=""
trap '[ -z "$SLEEPER" ] || kill "$SLEEPER" 2>/dev/null || true; seed_drop_db "$TGT"' EXIT
fail() { echo "FAIL(test) $*"; FAILS=$((FAILS + 1)); }

if [ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$TPL' AND datistemplate")" != "1" ]; then
  echo "== build $TPL at scale 0.01"
  SEED_TEMPLATE_DB=$TPL SEED_SCALE=0.01 bash "$SEED/build-template.sh" > /dev/null
fi

# 고정 조회: 표별 행 수 + 사용자·상품·주문·결제·원장 내용의 md5
fingerprint() {
  seed_psql "$1" -Atc "
    SELECT 'users=' || count(*) || ' ' || md5(string_agg(id || email || grade || password, ',' ORDER BY id)) FROM users
    UNION ALL SELECT 'product=' || count(*) || ' ' || md5(string_agg(id || ':' || stock || status || price, ',' ORDER BY id)) FROM product
    UNION ALL SELECT 'orders=' || count(*) || ' ' || md5(string_agg(id || status || total_amount, ',' ORDER BY id)) FROM orders
    UNION ALL SELECT 'order_item=' || count(*) FROM order_item
    UNION ALL SELECT 'payment=' || count(*) || ' ' || md5(string_agg(id || status || amount, ',' ORDER BY id)) FROM payment
    UNION ALL SELECT 'payment_outbox=' || count(*) FROM payment_outbox
    UNION ALL SELECT 'stock_ledger=' || count(*) || ' ' || md5(string_agg(id || ':' || qty || applied, ',' ORDER BY id)) FROM stock_ledger
    UNION ALL SELECT 'coupon_issue=' || count(*) FROM coupon_issue
    UNION ALL SELECT 'notifications=' || count(*) FROM notifications"
}

echo "== first reset"
out="$(SEED_TEMPLATE_DB=$TPL bash "$SEED/reset.sh" "$TGT")"
echo "$out"
echo "$out" | grep -Eq 'seconds' || fail "reset.sh prints no elapsed seconds"
want="$(fingerprint "$TPL")"
[ "$(fingerprint "$TGT")" = "$want" ] || fail "target after first reset differs from template"

echo "== damage target, keep a session open on it"
seed_psql "$TGT" -c "
  DELETE FROM notifications WHERE id % 3 = 0;
  DELETE FROM coupon_issue WHERE id % 2 = 0;
  UPDATE users SET grade = 'NORMAL', password = 'x' WHERE id % 7 = 0;
  UPDATE product SET stock = 0 WHERE id::text < '8';
  UPDATE payment SET status = 'FAILED' WHERE status = 'DONE' AND id::text < '4';
  DELETE FROM stock_ledger WHERE id % 5 = 0;
  UPDATE orders SET total_amount = total_amount + 1 WHERE id::text < '3'" > /dev/null
[ "$(fingerprint "$TGT")" != "$want" ] || fail "damage did not change the fingerprint"
seed_psql "$TGT" -c "SELECT pg_sleep(120)" > /dev/null 2>&1 &
SLEEPER=$!
sleep 1

echo "== second reset (a session is open on the target)"
SEED_TEMPLATE_DB=$TPL bash "$SEED/reset.sh" "$TGT"
kill "$SLEEPER" 2>/dev/null || true
wait "$SLEEPER" 2>/dev/null || true
SLEEPER=""
[ "$(fingerprint "$TGT")" = "$want" ] || fail "target after second reset differs from template"

echo "== refusals"
for bad in shopping_db_dev "$TPL" postgres template1; do
  set +e
  SEED_TEMPLATE_DB=$TPL bash "$SEED/reset.sh" "$bad" > /dev/null 2>&1
  code=$?
  set -e
  [ "$code" -eq 2 ] || fail "reset.sh $bad exit $code, want 2"
done
for keep in shopping_db_dev "$TPL" postgres; do
  [ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$keep'")" = 1 ] || fail "database $keep is gone"
done

if [ "$FAILS" -ne 0 ]; then echo "reset-test: $FAILS failure(s)"; exit 1; fi
echo "reset-test: OK"
