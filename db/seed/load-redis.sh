#!/usr/bin/env bash
# DB 값으로 Redis 키를 계산해 적재한다 — 애플리케이션의 워밍업 러너가 SETNX 로 심는 값과 같은 공식이다
# 사용: bash db/seed/load-redis.sh <database>
# 환경 변수(기본값):
#   SEED_REDIS_PORT       Redis 포트 (6379) — 그 포트를 게시한 컨테이너의 redis-cli 를 쓴다(SEED_REDIS_CONTAINER 로 직접 지정 가능)
#   SEED_PG_CONTAINER     PostgreSQL 컨테이너 (dev-db) · SEED_PG_USER (dev)
# 지우는 것: 이 스크립트가 소유한 키 패턴만 — product:stock:* · coupon:*:stock · coupon:*:issued_users · coupon:*:req · coupon:*:dlq
#   (SCAN 으로 찾아 UNLINK 한다. 키 전체 조회나 DB 비우기 명령은 쓰지 않으며 그 밖의 키는 그대로 둔다)
# 쓰는 것: product:stock:{id} = stock − 미적용 원장 qty 합 (flash_sale · REDIS 모드 상품만)
#          coupon:{id}:stock = max(quantity − 발급 행 수, 0) · coupon:{id}:issued_users = 발급 사용자 id 집합 (is_active 쿠폰만, 중괄호는 글자 그대로)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

[ $# -eq 1 ] || { echo "usage: load-redis.sh <database>" >&2; exit 2; }
DB="$1"
START="$(seed_now)"

echo "== delete owned keys on :$SEED_REDIS_PORT"
for pattern in 'product:stock:*' 'coupon:*:stock' 'coupon:*:issued_users' 'coupon:*:req' 'coupon:*:dlq'; do
  seed_rcli --scan --pattern "$pattern" | sed 's/^/UNLINK /' | seed_rcli_pipe > /dev/null
done

run_commands() {
  local label="$1" out
  out="$(seed_rcli_pipe)"
  if printf '%s\n' "$out" | grep -Eq '^(\(error\)|ERR|WRONGTYPE)'; then
    echo "redis error while loading $label:" >&2
    printf '%s\n' "$out" | grep -E '^(\(error\)|ERR|WRONGTYPE)' | head -3 >&2
    exit 1
  fi
}

echo "== load $DB"
seed_psql "$DB" -At -c "
  SELECT 'SET product:stock:' || p.id || ' ' || (p.stock - COALESCE((SELECT SUM(l.qty) FROM stock_ledger l WHERE l.product_id = p.id AND l.applied = false), 0))
    FROM product p WHERE p.flash_sale = true AND p.stock_mode = 'REDIS' AND p.stock IS NOT NULL" | run_commands product
seed_psql "$DB" -At -c "
  SELECT 'SET coupon:{' || c.id || '}:stock ' || greatest(c.quantity - (SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id), 0)
    FROM coupon c WHERE c.is_active = true AND c.quantity IS NOT NULL AND c.quantity > 0" | run_commands coupon-stock
# 발급 사용자는 쿠폰마다 1000 명씩 한 SADD 로 묶는다
seed_psql "$DB" -At -c "
  SELECT 'SADD coupon:{' || x.coupon_id || '}:issued_users ' || string_agg(x.user_id::text, ' ')
    FROM (SELECT i.coupon_id, i.user_id,
                 (row_number() OVER (PARTITION BY i.coupon_id ORDER BY i.user_id) - 1) / 1000 AS batch
            FROM coupon_issue i JOIN coupon c ON c.id = i.coupon_id WHERE c.is_active = true) x
   GROUP BY x.coupon_id, x.batch" | run_commands coupon-issued-users

count() { seed_rcli --scan --pattern "$1" | wc -l | tr -d ' '; }
END="$(seed_now)"
echo "loaded product:stock keys=$(count 'product:stock:*') coupon stock keys=$(count 'coupon:*:stock') coupon issued_users keys=$(count 'coupon:*:issued_users')"
echo "redis load seconds: $(perl -e "printf '%.1f', $END - $START")"
