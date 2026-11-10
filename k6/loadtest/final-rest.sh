#!/usr/bin/env bash
# r120 최종 측정 M-4 · M-6 · M-5 - 쿠폰 발급 · 상품 상세 조회 · PG 취소 장애. 시나리오마다 백엔드를 멈추고 새 시드 복제 → (시나리오 준비) → 앱 기동(가상 스레드, 누수 감지 끔) → 점검 → 실행
# 사용: bash k6/loadtest/final-rest.sh <출력 폴더> [coupon detail pgcancel 중 돌릴 것들, 기본 셋 다 이 순서]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/k6/loadtest/env.sh"
OUT="${1:?usage: final-rest.sh <out-dir> [coupon] [detail] [pgcancel]}"; shift
WHAT="${*:-coupon detail pgcancel}"
RUN="bash $ROOT/k6/loadtest/run.sh"
SEED_STAMP="${SEED_STAMP:-/tmp/r119-measure-seed-stamp}"
mkdir -p "$OUT"
cd "$ROOT"

stop_backends() { docker stop nginx r119-measure-backend1-1 r119-measure-backend2-1 r119-measure-backend3-1 > /dev/null 2>&1 || true; }
boot() {
  SPRING_THREADS_VIRTUAL_ENABLED=true $RUN app > "$OUT/app-last.log" 2>&1 || { echo "app failed" >&2; return 1; }
  bash k6/loadtest/check-env.sh > "$OUT/check-last.log" 2>&1 || { echo "check-env failed" >&2; return 1; }
}
reseed() { $RUN seed > "$OUT/seed-last.log" 2>&1 || { echo "seed failed" >&2; return 1; }; }
# 백엔드 이름별 요청 수 - nginx 로그에서 URI 정규식에 맞는 줄을 $upstream_addr 로 센다(backends.tsv 는 컨테이너 IP → 이름)
backend_split() { # 폴더 · URI 정규식 · nginx 로그 줄 시작
  for b in $BACKENDS; do echo "$b $(docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJECT}_app-network\").IPAddress}}" "${PROJECT}-$b-1")"; done > "$1/backends.tsv"
  docker exec nginx tail -n +"$3" "$NGINX_LOG" > "$1/nginx.log"
  python3 -B - "$1" "$2" <<'PY'
import json, re, sys, os
d, rx = sys.argv[1:3]
ip = {l.split()[1]: l.split()[0] for l in open(os.path.join(d, "backends.tsv")) if l.strip()}
cnt = {}; st = {}
for l in open(os.path.join(d, "nginx.log")):
    f = l.split(None, 5)
    if len(f) < 6 or not re.search(rx, f[2]): continue
    st[f[3]] = st.get(f[3], 0) + 1
    a = f[5].rsplit(None, 1)[0] if " " in f[5] else f[5]
    n = ip.get(a.split(",")[-1].strip().split(":")[0], "unknown")
    cnt[n] = cnt.get(n, 0) + 1
json.dump({"backend_requests": cnt, "nginx_status": st}, open(os.path.join(d, "split.json"), "w"))
PY
}
# 이 시나리오의 조건 - 스레드 종류 · 누수 감지 문턱값은 떠 있는 backend1 컨테이너의 환경에서, 시드 복제 시각은 run.sh seed 가 남긴 SEED_STAMP 에서 읽는다
write_condition() { # 시나리오 폴더
  local vt lk
  vt="$(docker exec "${PROJECT}-backend1-1" printenv SPRING_THREADS_VIRTUAL_ENABLED 2> /dev/null || echo true)"
  lk="$(docker exec "${PROJECT}-backend1-1" printenv SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD 2> /dev/null || echo 0)"
  printf '{"threads": "%s", "leak_detection": %s, "diag_samplers": false, "seed_copy": "%s"}\n' \
    "$([ "$vt" = false ] && echo platform || echo virtual)" "$([ "${lk:-0}" -gt 0 ] && echo true || echo false)" "$(cat "$SEED_STAMP" 2> /dev/null || echo unknown)" > "$1/condition.json"
}
nginx_lines() { docker exec nginx sh -c "wc -l < $NGINX_LOG" | tr -d ' '; }

coupon() {
  local d="$OUT/coupon"; mkdir -p "$d"
  echo "== $(date -u +%T) coupon"
  stop_backends; reseed || return 1
  local qty="${COUPON_QTY:-1000}" rate="${COUPON_RATE:-500}" dur="${COUPON_DURATION_S:-6}"
  # 새 쿠폰 하나(수량 qty, 발급 행 없음) - 백엔드가 멈춘 동안 DB 에 넣고 Redis 재고 키를 다시 적재한다
  local cid
  cid="$(psql_seed -Atc "INSERT INTO coupon (id, name, description, quantity, amount, minimum_cost, is_active, type, expire_date, created_at, updated_at)
    SELECT max(id) + 1, 'r120 measure coupon', 'r120 measure coupon', $qty, 5000, 30000, true, 'DISCOUNT', (now() AT TIME ZONE 'Asia/Seoul')::date + 30, now() AT TIME ZONE 'Asia/Seoul', now() AT TIME ZONE 'Asia/Seoul' FROM coupon RETURNING id" | head -1)"
  bash db/seed/load-redis.sh "$SEED_DB" > "$d/load-redis.log" 2>&1
  boot || return 1
  write_condition "$d"
  psql_seed -c "SELECT pg_stat_statements_reset()" > /dev/null
  docker exec redis redis-cli GET "coupon:{$cid}:stock" > "$d/stock-before.txt"
  local before; before="$(nginx_lines)"
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null
  docker exec redis redis-cli SLOWLOG RESET > /dev/null
  local t0; t0="$(date -u +%FT%TZ)"
  k6 run -q --no-usage-report -e COUPON_ID="$cid" -e RATE="$rate" -e DURATION_S="$dur" -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$d/k6-summary.json" \
    k6/scripts/coupon-issue-final.js > "$d/k6.log" 2>&1 || echo "k6 exit $?" >> "$d/k6.log"
  sleep 5
  # 증거 - Redis 명령 통계 · 느린 명령, 백엔드 로그의 경고 · 오류와 쿠폰 소비자 줄
  docker exec redis redis-cli INFO commandstats > "$d/redis-commandstats.txt"
  docker exec redis redis-cli SLOWLOG GET 20 > "$d/redis-slowlog.txt"
  for b in $BACKENDS; do
    docker logs --since "$t0" "${PROJECT}-$b-1" 2>&1 | grep -E ' (WARN|ERROR) ' | head -100 > "$d/warn-$b.log" || true
    docker logs --since "$t0" "${PROJECT}-$b-1" 2>&1 | grep -c -iE 'coupon' > "$d/coupon-lines-$b.txt" || true
  done
  psql_seed -At -F $'\t' -c "SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 120), calls, round(mean_exec_time::numeric, 2) FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') ORDER BY total_exec_time DESC LIMIT 8" > "$d/pgss.tsv" || true
  backend_split "$d" '/coupon/issue-async/[0-9]+$' "$((before + 1))"
  psql_seed -Atc "SELECT count(*) FROM coupon_issue WHERE coupon_id = $cid" > "$d/issued-db.txt"
  docker exec redis redis-cli GET "coupon:{$cid}:stock" > "$d/stock-after.txt"
  docker exec redis redis-cli SCARD "coupon:{$cid}:issued_users" > "$d/issued-users-redis.txt"
  echo "$t0" > "$d/start.txt"
  python3 -B "$ROOT/k6/loadtest/derive-final.py" coupon "$d" "$OUT/coupon.json"
}

detail() {
  local d="$OUT/detail"; mkdir -p "$d"
  echo "== $(date -u +%T) detail"
  stop_backends; reseed || return 1
  boot || return 1
  write_condition "$d"
  local n="${DETAIL_PRODUCTS:-200}" rate="${DETAIL_RATE:-2000}" hold="${DETAIL_HOLD_S:-30}"
  psql_seed -Atc "SELECT id FROM product WHERE flash_sale = false ORDER BY id LIMIT $n" > "$d/product-ids.txt"
  # 기준선 - 10 초 동안 부하 없이 Redis GET 이 늘어나는 양(백엔드의 다른 일)
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null
  sleep 10
  docker exec redis redis-cli INFO commandstats > "$d/redis-baseline.txt"
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null
  psql_seed -c "SELECT pg_stat_statements_reset()" > /dev/null
  local before; before="$(nginx_lines)"
  local t0; t0="$(date -u +%FT%TZ)"
  k6 run -q --no-usage-report -e PRODUCT_IDS_FILE="$d/product-ids.txt" -e RATE="$rate" -e HOLD_S="$hold" -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$d/k6-summary.json" \
    k6/scripts/product-detail-final.js > "$d/k6.log" 2>&1 || echo "k6 exit $?" >> "$d/k6.log"
  docker exec redis redis-cli INFO commandstats > "$d/redis-after.txt"
  psql_seed -At -F $'\t' -c "SELECT regexp_replace(left(query, 300), '\s+', ' ', 'g'), calls FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') ORDER BY calls DESC LIMIT 10" > "$d/pgss.tsv"
  backend_split "$d" '^/api/v1/product/[0-9a-f-]{36}$' "$((before + 1))"
  echo "$t0" > "$d/start.txt"
  # BACKGROUND_PGSS = 상품 조회 요청이 없는 실행(쿠폰 실행)의 pgss.tsv - 같은 횟수로 찍히는 배경 작업의 상품 select 를 본문 적재 수에서 뺀다(없으면 빼지 않는다)
  python3 -B "$ROOT/k6/loadtest/derive-final.py" detail "$d" "$OUT/detail.json" ${BACKGROUND_PGSS:+--background-pgss "$BACKGROUND_PGSS"}
}

pgcancel() {
  echo "== $(date -u +%T) pgcancel"
  stop_backends; reseed || return 1
  boot || return 1
  mkdir -p "$OUT/pgcancel"; write_condition "$OUT/pgcancel"
  bash k6/loadtest/final-pg-cancel.sh "$OUT/pgcancel" > "$OUT/pgcancel.run.log" 2>&1
  cp "$OUT/pgcancel/pg-cancel.json" "$OUT/pg-cancel.json" 2> /dev/null || true
  tail -2 "$OUT/pgcancel.run.log"
}

for w in $WHAT; do "$w"; done
echo "== $(date -u +%T) all done: $WHAT"
