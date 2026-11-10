#!/usr/bin/env bash
# T2-4 시험: load-redis.sh 가 쓴 키를 SQL 공식과 하나씩 대조한다
#   product:stock:{id} = stock − 미적용 qty 합 (REDIS 모드 플래시 상품만) · coupon:{id}:stock = max(quantity − 발급 행, 0)
#   coupon:{id}:issued_users 의 SCARD = 발급 행 수 (활성 쿠폰만) · 키 개수 = 행 개수 · 패턴 밖 키는 살아남는다
# 환경 변수: SEED_PG_CONTAINER(dev-db) SEED_REDIS_PORT(6379 — 이 시험은 16380 을 넘긴다) SEED_KAFKA_CONTAINER
# 지우는 것: shop_seed_t2b 와 load-redis.sh 가 소유한 패턴의 키, 이 시험이 심은 t2:* 키
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
. "$SEED/lib.sh"

TPL=shop_seed_t2a
CLONE=shop_seed_t2b
FAILS=0
TMP="$(mktemp -d)"
trap 'seed_drop_db "$CLONE"; seed_rcli DEL t2:keep coupon:issue:status:t2keep refresh:t2keep > /dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
fail() { echo "FAIL(test) $*"; FAILS=$((FAILS + 1)); }

if [ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$TPL' AND datistemplate")" != "1" ]; then
  echo "== build $TPL at scale 0.01"
  SEED_TEMPLATE_DB=$TPL SEED_SCALE=0.01 bash "$SEED/build-template.sh" > /dev/null
fi

scan() { seed_rcli --scan --pattern "$1" | sort; }

# 한 데이터베이스에 대해 적재 → 대조
check_db() {
  local db="$1"
  echo "== load $db"
  # 소유 패턴의 묵은 키(지워져야 함)와 패턴 밖 키(남아야 함)를 심는다
  seed_rcli SET product:stock:00000000-0000-4000-8000-000000000000 9 > /dev/null
  seed_rcli SET 'coupon:{777777}:stock' 5 > /dev/null
  seed_rcli SADD 'coupon:{777777}:issued_users' 1 > /dev/null
  seed_rcli XADD 'coupon:{777777}:req' '*' a b > /dev/null
  seed_rcli XADD 'coupon:{777777}:dlq' '*' a b > /dev/null
  seed_rcli SET t2:keep 1 > /dev/null
  seed_rcli SET coupon:issue:status:t2keep 1 > /dev/null
  seed_rcli SET refresh:t2keep 1 > /dev/null

  bash "$SEED/load-redis.sh" "$db"

  # 묵은 키 삭제
  [ "$(seed_rcli EXISTS product:stock:00000000-0000-4000-8000-000000000000)" = 0 ] || fail "$db: stale product key survived"
  for k in stock issued_users req dlq; do
    [ "$(seed_rcli EXISTS "coupon:{777777}:$k")" = 0 ] || fail "$db: stale coupon key $k survived"
  done
  # 패턴 밖 키 보존
  for k in t2:keep coupon:issue:status:t2keep refresh:t2keep; do
    [ "$(seed_rcli EXISTS "$k")" = 1 ] || fail "$db: foreign key $k was deleted"
  done

  # 상품: SQL 공식 대 Redis
  seed_psql "$db" -At -c "
    SELECT 'product:stock:' || p.id || '=' || (p.stock - COALESCE((SELECT SUM(l.qty) FROM stock_ledger l WHERE l.product_id = p.id AND l.applied = false), 0))
      FROM product p WHERE p.flash_sale = true AND p.stock_mode = 'REDIS' ORDER BY 1" | sort > "$TMP/product.want"
  : > "$TMP/product.got"
  while read -r k; do [ -z "$k" ] || echo "$k=$(seed_rcli GET "$k")" >> "$TMP/product.got"; done < <(scan 'product:stock:*')
  sort -o "$TMP/product.got" "$TMP/product.got"
  diff "$TMP/product.want" "$TMP/product.got" > /dev/null || { fail "$db: product keys differ"; diff "$TMP/product.want" "$TMP/product.got" | head -5; }
  [ -s "$TMP/product.want" ] || fail "$db: no flash product in REDIS mode — the comparison is empty"

  # 쿠폰: 활성 쿠폰마다 재고 값 · 발급자 SCARD
  seed_psql "$db" -At -c "
    SELECT 'coupon:{' || c.id || '}:stock=' || greatest(c.quantity - (SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id), 0)
      FROM coupon c WHERE c.is_active = true ORDER BY 1" | sort > "$TMP/stock.want"
  : > "$TMP/stock.got"
  while read -r k; do [ -z "$k" ] || echo "$k=$(seed_rcli GET "$k")" >> "$TMP/stock.got"; done < <(scan 'coupon:*:stock')
  sort -o "$TMP/stock.got" "$TMP/stock.got"
  diff "$TMP/stock.want" "$TMP/stock.got" > /dev/null || { fail "$db: coupon stock keys differ"; diff "$TMP/stock.want" "$TMP/stock.got" | head -5; }
  [ -s "$TMP/stock.want" ] || fail "$db: no active coupon — the comparison is empty"

  seed_psql "$db" -At -c "
    SELECT 'coupon:{' || c.id || '}:issued_users=' || count(*)
      FROM coupon c JOIN coupon_issue i ON i.coupon_id = c.id WHERE c.is_active = true GROUP BY c.id ORDER BY 1" | sort > "$TMP/set.want"
  : > "$TMP/set.got"
  while read -r k; do [ -z "$k" ] || echo "$k=$(seed_rcli SCARD "$k")" >> "$TMP/set.got"; done < <(scan 'coupon:*:issued_users')
  sort -o "$TMP/set.got" "$TMP/set.got"
  diff "$TMP/set.want" "$TMP/set.got" > /dev/null || { fail "$db: issued_users sets differ"; diff "$TMP/set.want" "$TMP/set.got" | head -5; }
  [ -s "$TMP/set.want" ] || fail "$db: no issued user — the comparison is empty"

  # 요청 · DLQ 스트림은 이 스크립트가 만들지 않는다
  [ -z "$(scan 'coupon:*:req')" ] || fail "$db: coupon req stream key written"
  [ -z "$(scan 'coupon:*:dlq')" ] || fail "$db: coupon dlq stream key written"
}

check_db "$TPL"

echo "== clone with one flash product in DB mode and one normal product made flash"
seed_drop_db "$CLONE"
seed_psql postgres -c "CREATE DATABASE $CLONE TEMPLATE $TPL" > /dev/null
flash_id="$(seed_psql "$CLONE" -Atc "SELECT id FROM product WHERE flash_sale ORDER BY id LIMIT 1")"
seed_psql "$CLONE" -c "
  UPDATE product SET stock_mode = 'DB' WHERE id = '$flash_id';
  UPDATE product SET flash_sale = true, stock = 77 WHERE id = (SELECT id FROM product WHERE flash_sale = false AND stock >= 77 ORDER BY id LIMIT 1)" > /dev/null
check_db "$CLONE"
[ "$(seed_rcli EXISTS "product:stock:$flash_id")" = 0 ] || fail "DB-mode flash product has a Redis key"

if [ "$FAILS" -ne 0 ]; then echo "redis-load-test: $FAILS failure(s)"; exit 1; fi
echo "redis-load-test: OK"
