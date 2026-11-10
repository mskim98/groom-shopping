#!/usr/bin/env bash
# T2-2 시험: scale 0.01 DB(shop_seed_t2a)를 만들어 verify.sh 가 통과하는지 보고,
# 검사 묶음마다 불변식 하나를 깨뜨린 복제본에서 verify.sh 가 그 묶음을 실패로 짚는지 본다
# 환경 변수: SEED_PG_CONTAINER(dev-db) SEED_REDIS_PORT SEED_KAFKA_CONTAINER — 지우는 DB 는 shop_seed_t2a · shop_seed_t2a_work 뿐
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
. "$SEED/lib.sh"

BASE=shop_seed_t2a
WORK=shop_seed_t2a_work
FAILS=0
trap 'seed_drop_db "$WORK"' EXIT

note() { printf '%s\n' "$*"; }
fail() { note "FAIL(test) $*"; FAILS=$((FAILS + 1)); }

note "== build $BASE at scale 0.01"
SEED_TEMPLATE_DB=$BASE SEED_SCALE=0.01 bash "$SEED/build-template.sh" > /dev/null

note "== clean database passes"
if ! bash "$SEED/verify.sh" "$BASE" 0.01; then fail "clean $BASE: verify.sh exit != 0"; fi

# 한 줄 = 검사 이름 | 그 묶음만 깨뜨리는 SQL
breaks() {
  cat <<'LIST'
counts|DELETE FROM notifications WHERE id % 5 = 0
flyway|DELETE FROM flyway_schema_history WHERE version = '23'
refs|UPDATE payment_outbox SET aggregate_id = gen_random_uuid() WHERE id = (SELECT min(id) FROM payment_outbox)
enums|UPDATE users SET grade = 'NORMAL' WHERE id = (SELECT min(id) FROM users WHERE email LIKE 'seed\_%')
amounts|UPDATE orders SET sub_total = sub_total + 1 WHERE id = (SELECT min(id) FROM orders)
flash-stock|UPDATE stock_ledger SET qty = qty + 100000000 WHERE id = (SELECT min(id) FROM stock_ledger WHERE applied = false AND kind = 'SALE')
coupon-quota|UPDATE coupon SET quantity = 1 WHERE id = (SELECT max(id) FROM coupon)
sequences|SELECT setval('users_id_seq', 1)
minority|UPDATE payment SET status = 'DONE' WHERE status = 'READY'
skew|UPDATE order_item SET product_id = (SELECT id FROM product WHERE flash_sale = false ORDER BY id OFFSET (order_item.id % 300) LIMIT 1)
raffle|DELETE FROM raffles WHERE raffle_id = (SELECT min(raffle_id) FROM raffles)
LIST
}

while IFS='|' read -r check sql; do
  note "== break [$check]"
  seed_drop_db "$WORK"
  seed_psql postgres -c "CREATE DATABASE $WORK TEMPLATE $BASE" > /dev/null
  seed_psql "$WORK" -c "$sql" > /dev/null
  set +e
  out="$(bash "$SEED/verify.sh" "$WORK" 0.01 2>&1)"
  code=$?
  set -e
  if [ "$code" -ne 1 ]; then fail "[$check] exit $code, want 1"; fi
  if ! printf '%s\n' "$out" | grep -q "^FAIL $check:"; then
    fail "[$check] no 'FAIL $check:' line"
    printf '%s\n' "$out" | grep '^FAIL' || true
  fi
done < <(breaks)

if [ "$FAILS" -ne 0 ]; then note "verify-test: $FAILS failure(s)"; exit 1; fi
note "verify-test: OK"
