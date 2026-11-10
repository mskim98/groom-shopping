#!/usr/bin/env bash
# T2-6 시험: scale 1 템플릿에서 shop_seed_t2b 를 reset 하고 Redis 를 적재한 뒤 백엔드를 18082 포트로 띄워
# 로그인(200 + accessToken) · 플래시 상품 상세(200) · 상품 목록 size=20(200, 항목 20개)을 확인하고 백엔드를 끈다(실패해도)
# 환경 변수: SEED_PG_CONTAINER(dev-db) SEED_REDIS_PORT(6379 — 이 시험은 16380 을 넘긴다) SEED_KAFKA_CONTAINER
#   SEED_BOOT_PORT(18082) SEED_BOOT_TIMEOUT(초, 기본 240)
# 지우는 것: shop_seed_t2b(reset.sh 가 다시 만든다) · load-redis.sh 의 소유 키 패턴 · 이 시험이 띄운 백엔드 프로세스
# 카프카 리스너는 SPRING_KAFKA_BOOTSTRAP_SERVERS 기본값(dev-kafka 9092)에 붙고 이 시험은 아무것도 보내지 않는다
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
ROOT="$(dirname "$(dirname "$SEED")")"
. "$SEED/lib.sh"

TGT=shop_seed_t2b
PORT="${SEED_BOOT_PORT:-18082}"
TIMEOUT="${SEED_BOOT_TIMEOUT:-240}"
LOG="$(mktemp -t boot-smoke.XXXXXX)"
BOOT_PID=""
FAILS=0
fail() { echo "FAIL(test) $*"; FAILS=$((FAILS + 1)); }

stop_backend() {
  [ -n "$BOOT_PID" ] || return 0
  # gradle 이 낳은 자식(JVM)까지 같은 프로세스 그룹이므로 그룹째 끈다
  kill -TERM -- "-$BOOT_PID" 2>/dev/null || kill -TERM "$BOOT_PID" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$BOOT_PID" 2>/dev/null || break; sleep 1; done
  kill -KILL -- "-$BOOT_PID" 2>/dev/null || true
  BOOT_PID=""
}
trap 'stop_backend; rm -f "$LOG"' EXIT

echo "== reset $TGT from ${SEED_TEMPLATE_DB:-shop_seed_template} and load Redis :$SEED_REDIS_PORT"
bash "$SEED/reset.sh" "$TGT"
bash "$SEED/load-redis.sh" "$TGT"

echo "== boot backend on :$PORT"
set -m
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/$TGT" \
SPRING_DATA_REDIS_PORT="$SEED_REDIS_PORT" \
SERVER_PORT="$PORT" \
  "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun > "$LOG" 2>&1 &
BOOT_PID=$!
set +m

BASE="http://localhost:$PORT/api"
up=0
for _ in $(seq 1 "$TIMEOUT"); do
  if ! kill -0 "$BOOT_PID" 2>/dev/null; then break; fi
  if grep -q "Started BackendApplication" "$LOG" 2>/dev/null; then up=1; break; fi
  sleep 1
done
if [ "$up" != 1 ]; then
  echo "backend did not start within ${TIMEOUT}s — last log lines:"
  tail -30 "$LOG"
  exit 1
fi
grep -Eq "Migrating|Successfully validated [0-9]+ migrations|Schema .* is up to date" "$LOG" || echo "(note: no Flyway line in the log)"

echo "== login"
login="$(curl -s -o "$LOG.login" -w '%{http_code}' -X POST "$BASE/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"seed_1@test.com","password":"loadtest123!"}' || true)"
[ "$login" = 200 ] || { fail "login status $login: $(head -c 300 "$LOG.login" 2>/dev/null)"; }
token="$(jq -r '.accessToken // .data.accessToken // empty' "$LOG.login" 2>/dev/null || true)"
rm -f "$LOG.login"
[ -n "$token" ] || fail "login response has no accessToken"

flash="f1a50000-0000-4000-8000-000000000001"
echo "== GET product/$flash"
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE/v1/product/$flash" || true)"
[ "$code" = 200 ] || fail "flash product status $code"

echo "== GET product?size=20"
code="$(curl -s -o "$LOG.list" -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE/v1/product?size=20" || true)"
[ "$code" = 200 ] || fail "product list status $code"
n="$(jq '.content | length' "$LOG.list" 2>/dev/null || echo -1)"
rm -f "$LOG.list"
[ "$n" = 20 ] || fail "product list has $n items, want 20"

if [ "$FAILS" -ne 0 ]; then
  echo "boot-smoke: $FAILS failure(s) — log tail:"
  tail -20 "$LOG"
  exit 1
fi
echo "boot-smoke: OK"
