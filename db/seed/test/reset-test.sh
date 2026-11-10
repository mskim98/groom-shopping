#!/usr/bin/env bash
# T2-3 시험: 템플릿 shop_seed_t2a(scale 0.01)에서 shop_seed_t2b 를 reset.sh 로 만들고, 행을 지우고 고친 뒤
# 다시 reset 해서 표별 행 수와 고정 조회의 md5 가 템플릿 값으로 돌아오는지, 거절 대상은 exit 2 이고 아무것도 안 지워지는지 본다
# 환경 변수: SEED_PG_CONTAINER(dev-db) SEED_REDIS_PORT SEED_KAFKA_CONTAINER
# 지우는 DB: shop_seed_t2b(trap) · 이 시험이 만든 미끼 DB shopx_decoy_t2(trap) — 템플릿 shop_seed_t2a 가 템플릿이 아니면 새로 만든다
# 거절 시험에는 실제 DB 이름을 넘기지 않는다: 없는 이름 shopping_db_zz_t2 · 미끼 shopx_decoy_t2 · 템플릿과 같은 shop_seed_t2a
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
. "$SEED/lib.sh"

TPL=shop_seed_t2a
TGT=shop_seed_t2b
DECOY=shopx_decoy_t2
ABSENT=shopping_db_zz_t2
FAILS=0
SLEEPER=""
trap '[ -z "$SLEEPER" ] || kill "$SLEEPER" 2>/dev/null || true; seed_drop_db "$TGT"; seed_psql postgres -c "DROP DATABASE IF EXISTS shopx_decoy_t2" > /dev/null 2>&1 || true' EXIT
fail() { echo "FAIL(test) $*"; FAILS=$((FAILS + 1)); }

if [ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$TPL' AND datistemplate")" != "1" ]; then
  echo "== build $TPL at scale 0.01"
  SEED_TEMPLATE_DB=$TPL SEED_SCALE=0.01 bash "$SEED/build-template.sh" > /dev/null
fi

# 고정 조회: public 스키마 모든 표의 행 수(pg_tables 를 돌며 센다) + 사용자·상품·주문·결제·원장 내용의 md5
fingerprint() {
  seed_psql_stdin "$1" -At <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I', tablename, tablename) FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
\gexec
SELECT 'users.md5 ' || md5(string_agg(id || email || grade || password, ',' ORDER BY id)) FROM users;
SELECT 'product.md5 ' || md5(string_agg(id || ':' || stock || status || price, ',' ORDER BY id)) FROM product;
SELECT 'orders.md5 ' || md5(string_agg(id || status || total_amount, ',' ORDER BY id)) FROM orders;
SELECT 'payment.md5 ' || md5(string_agg(id || status || amount, ',' ORDER BY id)) FROM payment;
SELECT 'stock_ledger.md5 ' || md5(string_agg(id || ':' || qty || applied, ',' ORDER BY id)) FROM stock_ledger;
SQL
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

echo "== refusals (no real database name is handed to reset.sh)"
seed_psql postgres -c "DROP DATABASE IF EXISTS $DECOY" > /dev/null
seed_psql postgres -c "CREATE DATABASE $DECOY" > /dev/null
seed_psql "$DECOY" -c "CREATE TABLE marker (v int); INSERT INTO marker VALUES (42)" > /dev/null
[ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$ABSENT'")" = 0 ] || fail "$ABSENT exists — pick another absent name"
for bad in "$ABSENT" "$DECOY" "$TPL"; do
  set +e
  SEED_TEMPLATE_DB=$TPL bash "$SEED/reset.sh" "$bad" > /dev/null 2>&1
  code=$?
  set -e
  [ "$code" -eq 2 ] || fail "reset.sh $bad exit $code, want 2"
done
[ "$(seed_psql "$DECOY" -Atc "SELECT v FROM marker")" = 42 ] || fail "decoy $DECOY lost its marker table"
[ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$ABSENT'")" = 0 ] || fail "$ABSENT was created"
[ "$(fingerprint "$TPL")" = "$want" ] || fail "template $TPL changed during the refusals"
[ "$(seed_psql postgres -Atc "SELECT datistemplate FROM pg_database WHERE datname = '$TPL'")" = t ] || fail "$TPL is no longer a template"

if [ "$FAILS" -ne 0 ]; then echo "reset-test: $FAILS failure(s)"; exit 1; fi
echo "reset-test: OK"
