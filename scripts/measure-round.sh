#!/usr/bin/env bash
#
# 1회차 측정 사이클 - 초기화 -> 부하 -> 정합성 단언 -> 결과 저장
#
# 사용: ./scripts/measure-round.sh <전략명> <회차>
#   예: COUPON_ID=1 ./scripts/measure-round.sh db-lock 1
#
# 전략명은 application.yml 의 coupon.issue.strategy 값과 같아야 한다
# 이 스크립트는 전략을 바꾸지 않는다 - 서버를 그 값으로 띄운 뒤 부르는 것이 전제다
# 라운드마다 재기동해야 하는 이유는 전략 선택이 기동 시점에 고정되기 때문이다
#
# 필요한 것: k6, docker(또는 host 의 psql·redis-cli), 기동된 백엔드
set -euo pipefail

STRATEGY="${1:?전략명을 지정하라 (db-lock | lua-then-lock | kafka)}"
ROUND="${2:?회차 번호를 지정하라 (1 | 2 | 3)}"

CID="${COUPON_ID:?COUPON_ID 를 지정하라}"
STOCK="${STOCK:-10000}"
USERS="${USERS:-100000}"
VUS="${VUS:-500}"
ITERATIONS="${ITERATIONS:-100000}"
BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
ACTUATOR="${ACTUATOR:-http://localhost:8080/api/actuator}"

# host 에 psql·redis-cli 가 없으면 컨테이너 안의 것을 쓴다
# docker-compose.yml 의 container_name 이 db · redis 로 고정돼 있어 이름으로 붙는다
DB_CONTAINER="${DB_CONTAINER:-db}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis}"
# 접속 정보는 compose 가 읽는 .env 와 같은 곳에서 가져온다
# 여기에 기본값을 따로 적으면 compose 와 어긋났을 때 "role 없음" 으로만 드러난다
if [ -f "$(dirname "${BASH_SOURCE[0]}")/../.env" ]; then
  set -a
  . "$(dirname "${BASH_SOURCE[0]}")/../.env"
  set +a
fi
PG_USER="${POSTGRES_USER:?POSTGRES_USER 가 필요하다 (.env 또는 환경변수)}"
PG_DB="${POSTGRES_DB:?POSTGRES_DB 가 필요하다 (.env 또는 환경변수)}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTDIR="$ROOT/backend/docs/measurements"
# 런북 SQL 은 실행 스크립트 옆에 둔다
# backend/docs/ 가 통째로 .gitignore 대상이라 그 아래 두면 추적되지 않고,
# 스크립트만 커밋되면 다음 사람이 받아도 돌릴 수가 없다
RUNBOOK="$ROOT/scripts/runbook"
STAMP="$(date +%Y-%m-%d)"
# 덮어쓰면 앞 회차의 증거가 사라진다 - 전략·회차·날짜를 파일명에 전부 넣는다
OUT="$OUTDIR/coupon_${STRATEGY}_r${ROUND}_${STAMP}.json"
METRICS="$OUTDIR/coupon_${STRATEGY}_r${ROUND}_${STAMP}_runtime.csv"

psql_run() {
  if command -v psql >/dev/null 2>&1 && [ -n "${DB_URL:-}" ]; then
    psql "$DB_URL" "$@"
  else
    docker exec -i "$DB_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" "$@"
  fi
}

psql_file() {
  local f="$1"; shift
  if command -v psql >/dev/null 2>&1 && [ -n "${DB_URL:-}" ]; then
    psql "$DB_URL" "$@" -f "$f"
  else
    # 컨테이너 안에는 파일이 없으므로 표준 입력으로 밀어 넣는다
    docker exec -i "$DB_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" "$@" -f - < "$f"
  fi
}

redis_run() {
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli "$@"
  else
    docker exec -i "$REDIS_CONTAINER" redis-cli "$@"
  fi
}

# 상태 키는 요청 1건당 1개라 10만 개가 된다
# 한 번에 넘기면 인자 길이 상한에 걸리고, 1개씩 부르면 docker exec 왕복이 10만 번이라
# 2,000 개씩 끊어 보낸다
STATUS_BATCH="${STATUS_BATCH:-2000}"

each_status_key_batch() {
  local file="$1" action="$2"
  local batch=() key
  while IFS= read -r key; do
    key="${key%$'\r'}"
    [ -z "$key" ] && continue
    batch+=("$key")
    if [ "${#batch[@]}" -ge "$STATUS_BATCH" ]; then
      redis_run "$action" "${batch[@]}"
      batch=()
    fi
  done < "$file"
  if [ "${#batch[@]}" -gt 0 ]; then
    redis_run "$action" "${batch[@]}"
  fi
}

# 키 이름에 중괄호가 있는 것은 오타가 아니다
# CouponStockRedisRepository 가 재고 키와 발급자 SET 을 같은 해시 슬롯에 묶으려고
# coupon:{couponId}:... 형태로 만든다. 중괄호를 빼면 존재하지 않는 키를 지우게 된다
STOCK_KEY="coupon:{${CID}}:stock"
ISSUED_KEY="coupon:{${CID}}:issued_users"
STATUS_PATTERN="coupon:issue:status:*"

echo "=============================================="
echo " 전략=${STRATEGY} 회차=${ROUND} 재고=${STOCK} VU=${VUS} 요청=${ITERATIONS}"
echo "=============================================="

echo "[0/7] 서버가 그 전략으로 떠 있는지 확인"
RUNNING="$(curl -fsS "${ACTUATOR}/env/coupon.issue.strategy" 2>/dev/null \
            | sed -n 's/.*"value":"\([^"]*\)".*/\1/p' | head -1 || true)"
if [ -n "$RUNNING" ] && [ "$RUNNING" != "$STRATEGY" ]; then
  # 인자와 다른 전략으로 측정하면 결과 파일명이 곧 거짓이 된다
  echo "  서버 전략이 '${RUNNING}' 인데 인자는 '${STRATEGY}' 다. 재기동 후 다시 실행하라" >&2
  exit 1
fi
echo "  서버 전략: ${RUNNING:-확인 불가(actuator env 미노출)}"
# 가상 스레드 여부는 결과 해석을 가른다. 회차마다 원장에 남겨 둔다
VT="$(curl -fsS "${ACTUATOR}/env/spring.threads.virtual.enabled" 2>/dev/null \
       | sed -n 's/.*"value":"\([^"]*\)".*/\1/p' | head -1 || true)"
echo "  가상 스레드: ${VT:-확인 불가}"

echo "[1/7] 부하 유저 수 확인"
HAVE="$(psql_run -tAc "SELECT COUNT(*) FROM users WHERE email LIKE 'lt\_%@test.com'")"
echo "  현재 ${HAVE} 명 / 필요 ${USERS} 명"
if [ "$HAVE" -lt "$USERS" ]; then
  echo "  모자라므로 일괄 생성한다"
  psql_file "$RUNBOOK/runbook_seed_users.sql" -v users="$USERS"
fi

# 부하 유저의 실제 id 목록을 파일로 넘긴다
# 순번에 기준값을 더하는 방식은 못 쓴다 - 다른 테스트가 만든 계정이 사이에 끼어들어
# id 가 연속이 아니다 (실측: 10만 구간에 98,256 명만 부하 유저였다)
# 그대로 뒀으면 헤더가 엉뚱한 사용자를 가리켜 중복·품절 집계가 조용히 틀어진다
IDS_FILE="$ROOT/k6/scripts/lt_user_ids.txt"
psql_run -tAc "SELECT id FROM users WHERE email LIKE 'lt\_%@test.com' ORDER BY id" \
  | tr -d ' \r' | sed '/^$/d' > "$IDS_FILE"
ID_COUNT="$(wc -l < "$IDS_FILE" | tr -d ' ')"
echo "  부하 유저 id 목록 ${ID_COUNT} 개 준비"
if [ "$ID_COUNT" -lt "$USERS" ]; then
  echo "  부하 유저가 ${ID_COUNT} 명뿐이다. 필요 ${USERS} 명" >&2
  exit 1
fi

echo "[2/7] DB 초기화"
psql_file "$RUNBOOK/runbook_init.sql" -v couponId="$CID" -v stock="$STOCK"

echo "[3/7] Redis 키 삭제 - 재고 · 발급자 SET · 앞 회차 상태 키"
redis_run DEL "$STOCK_KEY" "$ISSUED_KEY" >/dev/null
STATUS_KEYS="$(mktemp)"
redis_run --scan --pattern "$STATUS_PATTERN" > "$STATUS_KEYS" || true
if [ -s "$STATUS_KEYS" ]; then
  # 앞 회차의 SUCCESS 상태가 남으면 단언 4 의 WAITING 계수가 오염된다
  each_status_key_batch "$STATUS_KEYS" DEL >/dev/null
fi
rm -f "$STATUS_KEYS"

echo "[4/7] 웜업 - SETNX 로 키가 없을 때만 적재"
# SET 이 아니라 SETNX 인 이유: 이벤트 중 Redis 재고가 살아 있는 카운터라
# 덮어쓰면 차감은 끝났으나 DB 커밋 전이던 요청 수만큼 재고가 되살아난다
redis_run SETNX "$STOCK_KEY" "$STOCK"
redis_run CONFIG RESETSTAT >/dev/null

echo "[5/7] 부하 - 런타임 지표를 2초 간격으로 병행 수집"
# 앞 회차의 쿼리 통계가 남으면 이번 회차의 느린 쿼리를 가려낼 수 없다
psql_run -tAc "SELECT pg_stat_statements_reset()" >/dev/null 2>&1 || \
  echo "  (pg_stat_statements 미설치 - 느린 쿼리 판정은 건너뛴다)"
# 캐시 적중률은 누적값이라 회차 전후의 차이로 봐야 한다
CACHE_BEFORE="$(psql_run -tAc "SELECT sum(blks_hit) || ',' || sum(blks_read) FROM pg_stat_database WHERE datname='${PG_DB}'" | tr -d ' ')"

# DB 컨테이너의 CPU · 메모리. 사다리에서 "자원이 모자란가" 를 배제하는 칸이다
DBSTATS="$OUTDIR/coupon_${STRATEGY}_r${ROUND}_${STAMP}_dbstats.csv"
docker stats "$DB_CONTAINER" --format '{{.CPUPerc}},{{.MemUsage}}' > "$DBSTATS" 2>/dev/null &
DBSTATS_PID=$!
echo "ts,hikari_active,hikari_pending,hikari_timeout,tomcat_busy,tomcat_max,pg_active,pg_lock_waits" > "$METRICS"
# 지표는 /actuator/metrics 가 아니라 /actuator/prometheus 에서 긁는다
# 이 애플리케이션은 health · info · prometheus 만 노출하고 metrics 는 500 을 낸다
# 한 번의 요청에서 5개 값을 함께 뽑으므로 값들이 같은 순간의 것이 된다
sample_metrics() {
  while true; do
    local snap
    snap="$(curl -fsS "${ACTUATOR}/prometheus" 2>/dev/null || true)"
    pick() { printf '%s\n' "$snap" | awk -v m="$1" '$0 ~ "^"m"[{ ]" {print $NF; exit}'; }
    # DB 안에서 실제로 돌고 있는 세션 수와 잠금 대기 수
    # 커넥션 풀이 마른 것이 "DB 가 느려서" 인지 "요청이 많아서" 인지를 가르는 값이다
    local pg
    pg="$(psql_run -tAc "SELECT count(*) FILTER (WHERE state='active') || ',' || count(*) FILTER (WHERE wait_event_type='Lock') FROM pg_stat_activity WHERE datname='${PG_DB}'" 2>/dev/null | tr -d ' ')"
    printf '%s,%s,%s,%s,%s,%s,%s\n' "$(date +%H:%M:%S)" \
      "$(pick hikaricp_connections_active)" \
      "$(pick hikaricp_connections_pending)" \
      "$(pick hikaricp_connections_timeout_total)" \
      "$(pick tomcat_threads_busy_threads)" \
      "$(pick tomcat_threads_config_max_threads)" \
      "${pg:-,}" >> "$METRICS"
    sleep 2
  done
}

sample_metrics &
SAMPLER_PID=$!
# 부하가 중간에 죽어도 샘플러가 남지 않게 한다
trap 'kill "$SAMPLER_PID" "$DBSTATS_PID" 2>/dev/null || true' EXIT

LOAD_STARTED_AT=$(date +%s)
set +e
k6 run \
  -e BASE_URL="$BASE_URL" \
  -e COUPON_ID="$CID" \
  -e USERS="$USERS" \
  -e VUS="$VUS" \
  -e ITERATIONS="$ITERATIONS" \
  -e STRATEGY="$STRATEGY" \
  -e ROUND="$ROUND" \
  -e LT_USER_COUNT="$ID_COUNT" \
  -e DISTINCT_USERS="${DISTINCT_USERS:-}" \
  --summary-export="$OUT" \
  --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' \
  "$ROOT/k6/scripts/coupon-issue-test.js"
K6_EXIT=$?
set -e
LOAD_SECONDS=$(( $(date +%s) - LOAD_STARTED_AT ))
kill "$SAMPLER_PID" 2>/dev/null || true
wait "$SAMPLER_PID" 2>/dev/null || true
kill "$DBSTATS_PID" 2>/dev/null || true
wait "$DBSTATS_PID" 2>/dev/null || true
trap - EXIT
# k6 종료 코드가 0 이 아닌 것은 threshold 미달일 뿐이고 회차 실패가 아니다
# 라운드마다 기대치가 다르므로 값으로 기록하고 판정은 단언으로 한다
echo "  k6 종료 코드: ${K6_EXIT} (0 이 아니면 threshold 미달. 회차는 계속 판정한다)"

# 비동기 확정은 부하가 끝나도 아직 큐에 남아 있다
# 그 상태에서 발급 수를 세면 "덜 처리된 상태"가 미달로 잡혀, 라운드 비교가 성립하지 않는다
# 더 늘지 않을 때까지 기다린 뒤에 센다
if [ "$STRATEGY" = "kafka" ]; then
  echo "[5.5/7] 확정 배수 대기 - 발급 수가 더 늘지 않을 때까지"
  DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-300}"
  DRAIN_STARTED_AT=$(date +%s)
  PREV=-1
  STABLE=0
  while :; do
    CUR="$(psql_run -tAc "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ${CID}" | tr -d ' ')"
    if [ "$CUR" = "$PREV" ]; then
      STABLE=$((STABLE + 1))
    else
      STABLE=0
    fi
    PREV="$CUR"
    # 3회 연속 같은 값이면 배수가 끝난 것으로 본다. 1회로 판정하면 폴링 간격 사이의 정체를 완료로 읽는다
    [ "$STABLE" -ge 3 ] && break
    ELAPSED=$(( $(date +%s) - DRAIN_STARTED_AT ))
    if [ "$ELAPSED" -ge "$DRAIN_TIMEOUT" ]; then
      echo "  ${DRAIN_TIMEOUT}s 안에 멈추지 않았다 (마지막 ${CUR}건). 이 회차의 발급 수는 배수 미완 상태다" >&2
      break
    fi
    sleep 2
  done
  DRAIN_SECONDS=$(( $(date +%s) - DRAIN_STARTED_AT ))
  echo "  배수 ${DRAIN_SECONDS}s, 확정 ${PREV}건"
fi

echo "[6/7] 정합성 단언"
psql_file "$RUNBOOK/runbook_assert.sql" -v couponId="$CID" -v stock="$STOCK"

# 사람이 읽는 표와 별개로 값을 다시 뽑는다. 원장에 실을 수 있어야 마지막 종합표를 손으로 옮기지 않는다
A_ISSUED="$(psql_run -tAc "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ${CID}")"
A_DUP="$(psql_run -tAc "SELECT COUNT(*) FROM (SELECT user_id FROM coupon_issue WHERE coupon_id = ${CID} GROUP BY user_id HAVING COUNT(*) > 1) t")"
A_REMAIN="$(psql_run -tAc "SELECT quantity FROM coupon WHERE id = ${CID}")"

# 라운드마다 기대값이 반대라 스크립트가 직접 판정한다
# S0·S1 은 quantity 가 남은 수량이라 0 이어야 하고, S2 는 불변 발행 수량이라 STOCK 그대로여야 한다
if [ "$STRATEGY" = "kafka" ]; then
  EXPECT_QUANTITY="$STOCK"
else
  EXPECT_QUANTITY=0
fi
if [ "$A_REMAIN" = "$EXPECT_QUANTITY" ]; then
  echo "  [단언3] quantity=${A_REMAIN} (기대 ${EXPECT_QUANTITY}) PASS"
else
  echo "  [단언3] quantity=${A_REMAIN} (기대 ${EXPECT_QUANTITY}) FAIL" >&2
fi

echo "  [단언4] 미확정(WAITING) 상태 키 == 0"
STATUS_KEYS="$(mktemp)"
redis_run --scan --pattern "$STATUS_PATTERN" > "$STATUS_KEYS" || true
WAITING=0
if [ -s "$STATUS_KEYS" ]; then
  # 게이트는 통과했는데 확정도 실패도 기록되지 않은 건이다
  # 폴링 창이 짧아 못 본 것과 구별되지 않으므로 부하 종료 후에 센다
  WAITING="$(each_status_key_batch "$STATUS_KEYS" MGET | grep -c '^WAITING$' || true)"
fi
rm -f "$STATUS_KEYS"
echo "  waiting=${WAITING}"

echo "  [참고] 컨슈머 랙 - 0 이 아니면 아직 확정되지 않은 건이 남았다"
# 부하 종료 시점에 랙이 남아 있으면 단언 1 의 발급 수는 "덜 처리된 상태"라 미달과 구별되지 않는다
if command -v docker >/dev/null 2>&1; then
  docker exec -i "${KAFKA_CONTAINER:-kafka}" kafka-consumer-groups \
    --bootstrap-server localhost:9092 --group coupon-issue-group --describe 2>/dev/null \
    | awk 'NR==1 || $2=="coupon-issue-requests"' || echo "  (랙 조회 실패 - 컨테이너명을 KAFKA_CONTAINER 로 지정하라)"
fi

echo "  [참고] Redis 재고 · 발급자 SET"
echo "  stock=$(redis_run GET "$STOCK_KEY")  issued_users=$(redis_run SCARD "$ISSUED_KEY")"

echo "  [사다리] 캐시 적중률과 느린 쿼리"
CACHE_AFTER="$(psql_run -tAc "SELECT sum(blks_hit) || ',' || sum(blks_read) FROM pg_stat_database WHERE datname='${PG_DB}'" | tr -d ' ')"
# 이 회차 동안 실행된 쿼리만 총 실행시간 순으로 남긴다
psql_run -tAc "SELECT round(total_exec_time::numeric,1) || ' ms total | ' || calls || ' calls | ' || round(mean_exec_time::numeric,2) || ' ms avg | ' || left(query, 120) FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 15" \
  > "$OUTDIR/coupon_${STRATEGY}_r${ROUND}_${STAMP}_slowqueries.txt" 2>/dev/null || true
SLOW_TOP_MS="$(psql_run -tAc "SELECT round(max(mean_exec_time)::numeric,2) FROM pg_stat_statements WHERE calls > 100" 2>/dev/null | tr -d ' ')"

echo "[7/7] Redis 명령 통계 - 요청당 왕복 수 판정용"
redis_run INFO commandstats > "$OUTDIR/coupon_${STRATEGY}_r${ROUND}_${STAMP}_commandstats.txt"

echo "[원장] 회차 요약을 rounds.csv 에 추가"
LEDGER="$OUTDIR/rounds.csv" \
STRATEGY="$STRATEGY" ROUND="$ROUND" STAMP="$STAMP" \
SUMMARY="$OUT" RUNTIME="$METRICS" \
LOAD_SECONDS="$LOAD_SECONDS" K6_EXIT="$K6_EXIT" \
A_ISSUED="$A_ISSUED" A_DUP="$A_DUP" A_REMAIN="$A_REMAIN" A_WAITING="$WAITING" \
STOCK="$STOCK" VUS="$VUS" ITERATIONS="$ITERATIONS" \
DBSTATS="$DBSTATS" CACHE_BEFORE="$CACHE_BEFORE" CACHE_AFTER="$CACHE_AFTER" \
SLOW_TOP_MS="${SLOW_TOP_MS:-}" VIRTUAL_THREADS="${VT:-unknown}" \
python3 "$ROOT/scripts/append-round.py"

echo "----------------------------------------------"
echo "결과:      $OUT"
echo "런타임:    $METRICS"
echo "원장:      ${OUTDIR}/rounds.csv"
echo "Redis:     ${OUTDIR}/coupon_${STRATEGY}_r${ROUND}_${STAMP}_commandstats.txt"
