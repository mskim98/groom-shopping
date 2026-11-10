#!/usr/bin/env bash
# T2-4 시험: load-redis.sh 가 쓴 키를 SQL 공식과 하나씩 대조한다
#   product:stock:{id} = stock − 미적용 qty 합 (REDIS 모드 플래시 상품만) · coupon:{id}:stock = max(quantity − 발급 행, 0)
#   coupon:{id}:issued_users 의 SCARD = 발급 행 수 (활성 쿠폰만) · 키 개수 = 행 개수 · 패턴 밖 키는 살아남는다
# 환경 변수: SEED_PG_CONTAINER(dev-db) SEED_REDIS_PORT(6379 — 이 시험은 16380 을 넘긴다) SEED_KAFKA_CONTAINER
# 지우는 것: shop_seed_t2b(trap) · load-redis.sh 가 소유한 패턴의 키(적재 때)
# trap 이 지우는 키: t2:keep · coupon:issue:status:t2keep · refresh:t2keep(패턴 밖 보존 확인용) · product:stock:00000000-0000-4000-8000-000000000000 ·
#   coupon:{777777}:stock · coupon:{777777}:issued_users · coupon:{777777}:req · coupon:{777777}:dlq(소유 패턴 묵은 키 삭제 확인용)
# 템플릿 shop_seed_t2a 가 템플릿이 아니면 scale 0.01 로 새로 만든다
# 마지막 케이스는 Redis 의 maxmemory 를 1 · maxmemory-policy 를 noeviction 으로 바꿔 SET 이 거절되게 한다 — trap 이 시작 때 값으로 되돌린다
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
. "$SEED/lib.sh"

TPL=shop_seed_t2a
CLONE=shop_seed_t2b
FAILS=0
TMP="$(mktemp -d)"
OLD_MAXMEM=""
OLD_POLICY=""
# 케이스 도중 죽어도 Redis 설정이 남지 않게 시작 때 값으로 되돌린다(값을 읽기 전이면 아무것도 안 한다)
restore_redis_conf() {
  [ -z "$OLD_POLICY" ] || seed_rcli CONFIG SET maxmemory-policy "$OLD_POLICY" > /dev/null 2>&1 || true
  [ -z "$OLD_MAXMEM" ] || seed_rcli CONFIG SET maxmemory "$OLD_MAXMEM" > /dev/null 2>&1 || true
}
trap 'restore_redis_conf; seed_drop_db "$CLONE"; seed_rcli DEL t2:keep coupon:issue:status:t2keep refresh:t2keep product:stock:00000000-0000-4000-8000-000000000000 "coupon:{777777}:stock" "coupon:{777777}:issued_users" "coupon:{777777}:req" "coupon:{777777}:dlq" > /dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
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

# Redis 가 적재 명령을 거절하면(maxmemory 1 + noeviction → SET 이 OOM 응답) load-redis.sh 는 exit 1 과 적재 단계 이름을 낸다
# 소유 키 삭제(UNLINK)는 OOM 에 막히지 않아 스크립트가 거절을 피해 가지 못한다
echo "== load-redis.sh fails naming the step when Redis rejects a command"
OLD_MAXMEM="$(seed_rcli CONFIG GET maxmemory | tail -1)"
OLD_POLICY="$(seed_rcli CONFIG GET maxmemory-policy | tail -1)"
seed_rcli CONFIG SET maxmemory-policy noeviction > /dev/null
seed_rcli CONFIG SET maxmemory 1 > /dev/null
set +e
out="$(bash "$SEED/load-redis.sh" "$TPL" 2>&1)"
code=$?
set -e
restore_redis_conf
[ "$code" -eq 1 ] || fail "[redis rejects] exit $code, want 1"
printf '%s\n' "$out" | grep -q 'while loading product' || { fail "[redis rejects] no message naming the step 'while loading product'"; printf '%s\n' "$out" | tail -5; }
[ "$(seed_rcli CONFIG GET maxmemory | tail -1)" = "$OLD_MAXMEM" ] || fail "[redis rejects] maxmemory was not restored"

if [ "$FAILS" -ne 0 ]; then echo "redis-load-test: $FAILS failure(s)"; exit 1; fi
echo "redis-load-test: OK"
