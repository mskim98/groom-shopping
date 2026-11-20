#!/usr/bin/env bash
# r129 측정 구동기 - 실행 하나 = 새 시드 복제 → (준비) → 앱 SERVERS=1 → 부하 → 수집 → 요약(summary.json). cpu-lock.sh 안에서 부른다
# 사용: bash k6/loadtest/r129-run.sh <실행> <출력 폴더>      환경 변수로 방법 값을 덮어쓴다(시험 실행용, 기본값이 측정값)
#   coupon        쿠폰 동기 발급 - RATE(100) DURATION_S(30) COUPON_QTY(1000) STRATEGY(stream|db-lock) FAULT(0|1: 부하 시작 5 초 뒤 Redis 10 초 정지) DIAG(0|1: 진단 표본)
#   hotrow        핫 로우 - FLASH(true|false) RPS(25|50) RAMP_S(10) HOLD_S(30)
#   bulkhead      Toss 승인 지연 DELAY_MS(5000) · VUS(200) · HOLD_S(30)
#   ratelimit     nginx 속도 제한 - RATE(100) DURATION_S(20)
#   outbox        outbox 중복 - N_RPS(10) HOLD_S(10) (확정 수 = N_RPS x HOLD_S)
#   comp          후처리 실패(주문 CONFIRMED 갱신에 DB 트리거) - N_RPS(10) HOLD_S(20) COMP_MAX_WAIT_S(600)
#   stockview     재고 노출 지연(r131) - 일반 경로 상품 하나에 결제 PAYMENTS(100)건을 차례로 확정하고 건마다 상품 상세를 POLL_MS(50) 간격으로 읽는다(한도 LIMIT_MS 15000)
# r131: coupon 은 AUTH=token(기본)과 FAULT=1 이면 토큰 저장소 redis-token 은 두고 주 Redis 만 멈춘다 · hotrow 는 WRITERS(2) 로 결제 컨슈머 스레드 수와 payment-events 파티션 수를 함께 정한다
# 출력 폴더: raw/ (원시 파일) · summary.json (r129-summary.py 가 만든다)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/k6/loadtest/env.sh" || exit 1
[ "$SERVERS" = 1 ] || { echo "SERVERS=1 로만 돈다 (SERVERS=$SERVERS)" >&2; exit 2; }
RUNNAME="${1:?usage: r129-run.sh <run> <out-dir>}"; OUT="${2:?usage: r129-run.sh <run> <out-dir>}"
mkdir -p "$OUT/raw"; D="$OUT/raw"
RUN="bash $ROOT/k6/loadtest/run.sh"
WM="http://localhost:18089"
HOT_PRODUCT="f1a50000-0000-4000-8000-000000000001"
cd "$ROOT"
export PYTHONDONTWRITEBYTECODE=1

log() { echo "== $(date -u +%T) $*"; }
die() { echo "FAIL: $*" >&2; exit 1; }
epoch() { perl -MTime::HiRes=time -e 'printf "%.3f\n", time'; }

stack_up() { $RUN keys > /dev/null && $RUN infra > "$D/infra.log" 2>&1 || die "infra"; }
# 새 시드 복제 - 백엔드를 멈추고 시드를 다시 만든다. 인자 하나가 있으면 시드 직후 · 앱 기동 전에 그 함수를 부른다(DB · Redis 준비)
reseed() { stop_backends; $RUN seed > "$D/seed.log" 2>&1 || die "seed (tail: $(tail -3 "$D/seed.log" | tr '\n' ' '))"; [ -n "${1:-}" ] && "$1"; return 0; }
boot() { $RUN app > "$D/app.log" 2>&1 || die "app (tail: $(tail -3 "$D/app.log" | tr '\n' ' '))"; bash k6/loadtest/check-env.sh > "$D/check.log" 2>&1 || die "check-env ($(grep FAIL "$D/check.log" | head -2 | tr '\n' ' '))"; }
wm_reset() { curl -fsS -X DELETE "$WM/__admin/requests" > /dev/null; }
wm_journal() { curl -fsS "$WM/__admin/requests?limit=100000" > "$1"; }
pgss_snapshot() { psql_seed -At -F $'\t' -c "SELECT queryid, calls, left(regexp_replace(query, '\s+', ' ', 'g'), 200) FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB')" > "$1"; }
lag_total() { docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9093 --describe --group payment-event-group 2> /dev/null | awk '$2 == "payment-events" && $6 ~ /^[0-9]+$/ {s += $6} END {print s + 0}'; }
describe_group() { docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9093 --describe --group payment-event-group 2> /dev/null; }
wait_lag_zero() { local w=0; while [ "$(lag_total)" != 0 ] && [ "$w" -lt "${1:-120}" ]; do sleep 2; w=$((w + 2)); done; echo "$w"; }

SAMPLER_PIDS=""
start_samplers() { # 폴더 - 호스트 top(5 초) · Hikari 지표(2 초) · DB 연결 수(0.3 초)
  top -l 100000 -s 5 -o cpu -n 12 -stats pid,cpu,command > "$1/host-top.txt" 2>&1 & SAMPLER_PIDS="$!"
  : > "$1/prom.tsv"; : > "$1/db-connections.txt"
  ( while :; do scrape_metric '^hikaricp_connections_(active|pending|max)[{]' "$1/prom.tsv"; sleep 2; done ) & SAMPLER_PIDS="$SAMPLER_PIDS $!"
  ( while :; do psql_seed -Atc "SELECT count(*), count(*) FILTER (WHERE wait_event_type = 'Lock') FROM pg_stat_activity WHERE datname = '$SEED_DB'" >> "$1/db-connections.txt" 2> /dev/null; sleep 0.2; done ) & SAMPLER_PIDS="$SAMPLER_PIDS $!"
}
stop_samplers() { kill $SAMPLER_PIDS 2> /dev/null; wait $SAMPLER_PIDS 2> /dev/null; SAMPLER_PIDS=""; pkill -f "top -l 100000" 2> /dev/null; return 0; }
trap 'stop_samplers' EXIT

# 진단 표본(DIAG=1) - 1 초마다 백엔드의 GC 정지 · CPU · 스레드 · 힙 지표와 k6 · Docker VM 프로세스의 CPU 를 덧붙인다. 표본 자체가 CPU 를 조금 쓰므로 진단 실행에만 켠다
start_diag() { # 폴더
  : > "$1/diag-prom.tsv"; : > "$1/diag-cpu.tsv"
  ( while :; do
      local e; e="$(epoch)"
      docker exec nginx curl -fsS "http://backend1:8080/api/actuator/prometheus" 2> /dev/null \
        | grep -E '^(jvm_gc_pause_seconds_(count|sum|max)|process_cpu_usage|system_cpu_usage|jvm_threads_(live|peak)_threads|jvm_memory_used_bytes[{]area="heap"|hikaricp_connections_(active|pending)[{])' | sed "s/^/$e\t/" >> "$1/diag-prom.tsv"
      printf '%s\t%s\t%s\n' "$e" "$(ps -A -o %cpu=,comm= | awk '/(^| |\/)k6$/ {s += $1} END {print s + 0}')" "$(ps -A -o %cpu=,comm= | awk '/com.docker.backend|com.docker.virtualization/ {s += $1} END {print s + 0}')" >> "$1/diag-cpu.tsv"
      sleep 1
    done ) & SAMPLER_PIDS="$SAMPLER_PIDS $!"
}
write_meta() { # 키=값(JSON 값) 쌍들
  python3 - "$D/meta.json" "$RUNNAME" "$@" <<'PY'
import json, subprocess, sys
out, run = sys.argv[1:3]
head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
# base_commit = 이 라운드의 기준(제품 코드는 R131 의 토큰 연결 · 쓰기 수 설정이 더해진 것), tooling_commit = 실행한 저장소의 HEAD
m = {"run": run, "base_commit": "70c5f79", "tooling_commit": head}
for kv in sys.argv[3:]:
    k, v = kv.split("=", 1)
    m[k] = json.loads(v)
json.dump(m, open(out, "w"), indent=1)
PY
}
# 백엔드가 뜬 환경(토큰 저장소 호스트 · 쓰기 수)과 payment-events 토픽 · 컨슈머 그룹 설명을 남긴다 - 요약의 token_redis · writers 가 이 파일에서 나온다
# 토픽 설명은 이 시점의 실제 파티션 수, 그룹 설명은 파티션을 받은 컨슈머 수(스레드가 파티션보다 많으면 남는 스레드는 나오지 않는다)
capture_env() {
  docker exec "${PROJECT}-backend1-1" env 2> /dev/null | grep -E '^(TOKEN_REDIS_HOST|SPRING_DATA_REDIS_HOST|PAYMENT_CONSUMER_CONCURRENCY|PAYMENT_EVENTS_PARTITIONS)=' > "$D/backend-env.txt" || true
  docker exec kafka kafka-topics --bootstrap-server localhost:9093 --describe --topic payment-events > "$D/topic-describe.txt" 2>&1 || true
  describe_group > "$D/cg-describe.txt"
  docker inspect -f '{{.Name}} {{.State.Status}} paused={{.State.Paused}}' redis redis-token > "$D/redis-state-end.txt" 2>&1 || true
}
summarize() { python3 -B "$ROOT/k6/loadtest/r129-summary.py" "$1" "$D" "$OUT/summary.json" || die "summary"; }

# 부하 시작(k6 준비 단계 끝) 직후의 한 번 - pg_stat_statements 표본 · Redis 통계 초기화 · 쿠폰 단계 타이머 기준선
after_setup() { # 폴더 · k6 로그
  # k6 준비 단계(로그인)가 120 초 안에 끝나지 않으면 실패로 끝낸다 - 상한이 없으면 k6 가 죽은 실행이 영원히 기다린다(R129-13-6)
  local w=0
  until grep -q 'setup:' "$2" 2> /dev/null; do sleep 0.3; w=$((w + 1)); [ "$w" -lt 400 ] || { echo "k6 setup line not seen within 120 s ($2)" >&2; return 1; }; done
  epoch > "$1/load-start.epoch"
  pgss_snapshot "$1/pgss-setup.tsv"
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null; docker exec redis redis-cli SLOWLOG RESET > /dev/null
  scrape_metric '^coupon_stage_seconds_(count|sum)[{]' "$1/coupon-stages-before.tsv"
}

# 진단 표본(DIAG_R131=1, r131 코디네이터 요청) - 정지 뒤 요청이 끊긴 단계를 가리는 증거: 호스트에서 1 초마다 nginx 경유 건강 확인(응답 코드 또는 curl 오류) · docker 이벤트 ·
# 쿠폰 발급 URI 의 백엔드 요청 카운터(Tomcat 까지 온 요청 수)를 부하 전 · 후에 읽는다. 실행 끝에서 nginx · 백엔드 로그와 컨테이너 상태를 남긴다
start_r131_diag() { # 폴더 · 시작 시각
  : > "$1/host-health.tsv"
  ( while :; do
      local e c; e="$(epoch)"
      c="$(curl -s -o /dev/null --max-time 2 -w '%{http_code} %{time_total}' http://localhost/api/actuator/health 2> /dev/null; echo " curl_exit=$?")"
      printf '%s\t%s\n' "$e" "$c" >> "$1/host-health.tsv"
      sleep 1
    done ) & SAMPLER_PIDS="$SAMPLER_PIDS $!"
  docker events --since "$2" --format '{{.Time}} {{.Type}} {{.Action}} {{.Actor.Attributes.name}}' > "$1/docker-events.log" 2>&1 & SAMPLER_PIDS="$SAMPLER_PIDS $!"
  scrape_metric '^http_server_requests_seconds_count[{].*coupon' "$1/http-requests-before.tsv"
}
capture_r131_diag() { # 폴더 · 시작 시각
  scrape_metric '^http_server_requests_seconds_count[{].*coupon' "$1/http-requests-after.tsv"
  docker logs --since "$2" nginx > "$1/nginx-docker-logs.txt" 2>&1 || true
  docker logs --timestamps --since "$2" "${PROJECT}-backend1-1" > "$1/backend1-full.log" 2>&1 || true
  docker inspect -f '{{.Name}} status={{.State.Status}} started={{.State.StartedAt}} finished={{.State.FinishedAt}} restarts={{.RestartCount}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}' nginx "${PROJECT}-backend1-1" > "$1/container-state-end.txt" 2>&1 || true
}

# ── 쿠폰 ───────────────────────────────────────────────────────────────
coupon_run() {
  local rate="${RATE:-100}" dur="${DURATION_S:-30}" qty="${COUPON_QTY:-1000}" strategy="${STRATEGY:-stream}" fault="${FAULT:-0}" diag="${DIAG:-0}"
  local issue_path="${ISSUE_PATH:-issue}" auth="${AUTH:-token}"
  export COUPON_ISSUE_STRATEGY="$strategy"
  # AUTH=bypass 면 앱에 LOADTEST_AUTH_BYPASS_ENABLED=true 를 주고 k6 는 X-Lt-User-Id 헤더로 부른다
  if [ "$auth" = bypass ]; then export LOADTEST_AUTH_BYPASS_ENABLED=true; else export LOADTEST_AUTH_BYPASS_ENABLED=false; fi
  log "coupon rate=$rate dur=$dur qty=$qty strategy=$strategy fault=$fault"
  prep() {
    local cid
    cid="$(psql_seed -Atc "INSERT INTO coupon (id, name, description, quantity, amount, minimum_cost, is_active, type, expire_date, created_at, updated_at)
      SELECT max(id) + 1, 'r129 measure coupon', 'r129 measure coupon', $qty, 5000, 30000, true, 'DISCOUNT', (now() AT TIME ZONE 'Asia/Seoul')::date + 30, now() AT TIME ZONE 'Asia/Seoul', now() AT TIME ZONE 'Asia/Seoul' FROM coupon RETURNING id" | head -1)"
    echo "$cid" > "$D/coupon-id.txt"
    bash db/seed/load-redis.sh "$SEED_DB" > "$D/load-redis.log" 2>&1
  }
  reseed prep; boot
  local cid; cid="$(cat "$D/coupon-id.txt")"
  write_condition "$D"
  if [ "$auth" = bypass ]; then
    # 헤더의 사용자 id 1..N 이 모두 users 에 있는지 확인한다(없으면 발급 행의 외래 키가 깨진다). seed_<n> 계정의 id 와는 다르지만 발급 경로가 보는 것은 서로 다른 사용자 N 명이다
    psql_seed -At -F $'\t' -c "SELECT count(*), min(id), max(id) FROM users WHERE id BETWEEN 1 AND $((rate * dur))" > "$D/bypass-user-id-check.tsv"
  fi
  if [ "$diag" = 1 ]; then docker exec redis redis-cli CONFIG SET latency-monitor-threshold 1 > /dev/null; docker exec redis redis-cli LATENCY RESET > /dev/null; fi
  psql_seed -c "SELECT pg_stat_statements_reset()" > /dev/null
  docker exec redis redis-cli GET "coupon:{$cid}:stock" > "$D/stock-before.txt"
  local before; before="$(docker exec nginx sh -c "wc -l < $NGINX_LOG" | tr -d ' ')"
  local t0; t0="$(date -u +%FT%TZ)"; echo "$t0" > "$D/start.txt"
  : > "$D/k6.log"
  start_samplers "$D"
  [ "$diag" = 1 ] && start_diag "$D"
  [ "${DIAG_R131:-0}" = 1 ] && start_r131_diag "$D" "$t0"
  after_setup "$D" "$D/k6.log" & local pa=$!
  k6 run -q --no-usage-report -e COUPON_ID="$cid" -e ISSUE_PATH="$issue_path" -e RATE="$rate" -e DURATION_S="$dur" -e AUTH="$auth" -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$D/k6-summary.json" \
    k6/scripts/coupon-issue-final.js > "$D/k6.log" 2>&1 & local kpid=$!
  if [ "$fault" = 1 ]; then
    local fw=0
    until grep -q 'setup:' "$D/k6.log" 2> /dev/null; do sleep 0.3; fw=$((fw + 1)); [ "$fw" -lt 400 ] || { kill "$kpid" "$pa" 2> /dev/null; die "k6 setup line not seen within 120 s (fault branch, $D/k6.log)"; }; done
    sleep 5
    local p0 p1 u0 u1
    p0="$(epoch)"; docker pause redis > /dev/null; p1="$(epoch)"
    sleep 9
    # 정지 중에 두 Redis 의 상태를 읽는다 - 토큰 저장소가 running · paused=false 여야 인증이 주 Redis 와 무관하다
    docker inspect -f '{{.Name}} {{.State.Status}} paused={{.State.Paused}}' redis redis-token > "$D/redis-state-during-pause.txt" 2>&1 || true
    sleep 1
    u0="$(epoch)"; docker unpause redis > /dev/null; u1="$(epoch)"
    printf '{"kind":"redis-pause","pause_cmd_start":%s,"pause_cmd_end":%s,"unpause_cmd_start":%s,"unpause_cmd_end":%s}\n' "$p0" "$p1" "$u0" "$u1" > "$D/fault.json"
  fi
  wait "$kpid" || echo "k6 exit $?" >> "$D/k6.log"
  wait "$pa" || { kill "$kpid" 2> /dev/null; die "k6 setup line not seen within 120 s ($D/k6.log)"; }
  sleep 5
  [ "${DIAG_R131:-0}" = 1 ] && capture_r131_diag "$D" "$t0"
  stop_samplers
  pgss_snapshot "$D/pgss-end.tsv"
  scrape_metric '^coupon_issue_wake_total[{]' "$D/coupon-wake.tsv"
  scrape_metric '^coupon_stage_seconds_(count|sum)[{]' "$D/coupon-stages.tsv"
  docker exec redis redis-cli INFO commandstats > "$D/redis-commandstats.txt"
  docker exec redis redis-cli SLOWLOG GET 20 > "$D/redis-slowlog.txt"
  if [ "$diag" = 1 ]; then docker exec redis redis-cli LATENCY LATEST > "$D/redis-latency-latest.txt"; docker exec redis redis-cli LATENCY HISTORY command > "$D/redis-latency-history.txt" 2>&1; docker exec redis redis-cli INFO stats > "$D/redis-info-stats.txt"; fi
  docker exec nginx tail -n +"$((before + 1))" "$NGINX_LOG" > "$D/nginx.log"
  docker logs --timestamps --since "$t0" "${PROJECT}-backend1-1" 2>&1 | grep 'CB_STATE_CHANGE' > "$D/backend-cb.log"
  docker logs --since "$t0" "${PROJECT}-backend1-1" 2>&1 | grep -c 'COUPON_REDIS_UNAVAILABLE_DB_FALLBACK' > "$D/fallback-count.txt"
  docker logs --since "$t0" "${PROJECT}-backend1-1" 2>&1 | grep 'COUPON_REDIS_SLOW' > "$D/redis-slow-backend1.log" || true
  docker logs --since "$t0" "${PROJECT}-backend1-1" 2>&1 | grep -E ' (WARN|ERROR) ' | head -100 > "$D/warn-backend1.log" || true
  capture_env
  psql_seed -Atc "SELECT count(*) FROM coupon_issue WHERE coupon_id = $cid" > "$D/issued-db.txt"
  psql_seed -Atc "SELECT count(*) - count(DISTINCT user_id) FROM coupon_issue WHERE coupon_id = $cid" > "$D/duplicate-rows.txt"
  docker exec redis redis-cli GET "coupon:{$cid}:stock" > "$D/stock-after.txt"
  write_meta "rate=$rate" "duration_s=$dur" "quantity=$qty" "strategy=\"$strategy\"" "issue_path=\"$issue_path\"" "fault=$fault" "diag=$diag" "auth=\"$auth\""
  summarize coupon
}

# ── 핫 로우 ─────────────────────────────────────────────────────────────
hotrow_run() {
  local flash="${FLASH:?FLASH=true|false}" rps="${RPS:?RPS}" ramp="${RAMP_S:-10}" hold="${HOLD_S:-30}" writers="${WRITERS:-2}"
  # 스레드 수와 파티션 수는 같은 값 하나에서 온다 - 스레드가 파티션보다 많으면 남는 스레드는 일을 받지 못한다. 토픽은 백엔드가 뜰 때 이 값으로 만들어진다(seed 가 옛 토픽을 지운다)
  export PAYMENT_CONSUMER_CONCURRENCY="$writers" PAYMENT_EVENTS_PARTITIONS="$writers"
  log "hotrow flash=$flash rps=$rps ramp=$ramp hold=$hold writers=$writers"
  prep() {
    psql_seed -c "UPDATE product SET stock = 1000000, flash_sale = $flash, stock_mode = 'REDIS' WHERE id = '$HOT_PRODUCT'"
    bash db/seed/load-redis.sh "$SEED_DB" > "$D/load-redis.log" 2>&1
  }
  reseed prep; boot
  # 위의 값이 시드 복제 · 앱 기동 뒤에도 그대로인지 기록한다(기동 러너가 바꿀 수 있다)
  psql_seed -At -F $'\t' -c "SELECT flash_sale, stock, stock_mode FROM product WHERE id = '$HOT_PRODUCT'" > "$D/product-before.tsv"
  psql_seed -Atc "SELECT count(*) FROM stock_ledger WHERE product_id = '$HOT_PRODUCT' AND kind = 'SALE'" > "$D/ledger-sale-before.txt"
  psql_seed -Atc "SELECT count(*) FROM payment p JOIN orders o ON o.id = p.order_id JOIN order_item oi ON oi.order_id = o.id WHERE oi.product_id = '$HOT_PRODUCT' AND p.status = 'DONE'" > "$D/confirmed-before.txt"
  K6_SCRIPT=k6/scripts/payment-only-steps.js K6_PREPARE_SCRIPT=k6/scripts/payment-only-prepare.js FLASH_COUNT=1 DIAG_SAMPLERS=1 RAMP_S="$ramp" HOLD_S="$hold" \
    $RUN step "$rps" "$D" > "$D/run.log" 2>&1 || die "step (tail: $(tail -3 "$D/run.log" | tr '\n' ' '))"
  # 재고를 낮추는 UPDATE 는 총 시간이 작으면 run.sh 의 pgss.tsv(총 시간 상위 20)에 들지 않는다 - 문장만 따로 읽는다(r129 flash-50-2 부터)
  psql_seed -At -F $'\t' -c "SELECT regexp_replace(left(query, 400), '\s+', ' ', 'g'), calls, round(mean_exec_time::numeric, 3), round(total_exec_time::numeric, 1)
    FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') AND query ~* '^\s*update\s+product\s' ORDER BY total_exec_time DESC" > "$D/pgss-stock-update.tsv"
  sleep 6 # 원장 적용 배치(1 초 주기)가 남은 행을 product.stock 에 반영할 시간
  psql_seed -At -F $'\t' -c "SELECT stock FROM product WHERE id = '$HOT_PRODUCT'" > "$D/stock-after.txt"
  psql_seed -Atc "SELECT count(*) FROM stock_ledger WHERE product_id = '$HOT_PRODUCT' AND kind = 'SALE'" > "$D/ledger-sale-after.txt"
  psql_seed -Atc "SELECT count(*) FROM payment p JOIN orders o ON o.id = p.order_id JOIN order_item oi ON oi.order_id = o.id WHERE oi.product_id = '$HOT_PRODUCT' AND p.status = 'DONE'" > "$D/confirmed-after.txt"
  psql_seed -Atc "SELECT count(DISTINCT oi.product_id) FROM order_item oi WHERE oi.order_id IN ($(order_ids "$D/step-$rps/payonly-orders.json"))" > "$D/product-count.txt"
  capture_env
  write_meta "flash_sale=$flash" "rps=$rps" "ramp_s=$ramp" "hold_s=$hold" "writers=$writers"
  summarize hotrow
}
# payonly-orders.json 의 주문 번호를 SQL IN 목록('a','b',…)으로
order_ids() { python3 -c "import json,sys; print(','.join(\"'%s'\" % o['orderId'] for o in json.load(open(sys.argv[1]))['orders']))" "$1"; }

# ── 재고 노출 지연 ──────────────────────────────────────────────────────
# 일반 경로(flash_sale=false) 상품 하나 - 결제 확정이 커밋될 때 컨슈머가 조건부 UPDATE 로 product.stock 을 낮추고, 커밋 뒤 StockQuantityInvalidator 가 수량 캐시를 지운다
stockview_run() {
  local payments="${PAYMENTS:-100}" poll="${POLL_MS:-50}" limit="${LIMIT_MS:-15000}"
  log "stockview payments=$payments poll=${poll}ms limit=${limit}ms"
  prep() {
    psql_seed -c "UPDATE product SET stock = 1000000, flash_sale = false, stock_mode = 'REDIS' WHERE id = '$HOT_PRODUCT'"
    bash db/seed/load-redis.sh "$SEED_DB" > "$D/load-redis.log" 2>&1
  }
  reseed prep; boot
  psql_seed -At -F $'\t' -c "SELECT flash_sale, stock, stock_mode FROM product WHERE id = '$HOT_PRODUCT'" > "$D/product-before.tsv"
  psql_seed -Atc "SELECT count(*) FROM stock_ledger WHERE product_id = '$HOT_PRODUCT' AND kind = 'SALE'" > "$D/ledger-sale-before.txt"
  psql_seed -Atc "SELECT count(*) FROM payment p JOIN orders o ON o.id = p.order_id JOIN order_item oi ON oi.order_id = o.id WHERE oi.product_id = '$HOT_PRODUCT' AND p.status = 'DONE'" > "$D/confirmed-before.txt"
  # run.sh step 의 준비 단계가 만들 주문 수는 rps x (램프 + 2 x 유지) x 0.55 + 20 이다 - rps 1 · 램프 0 · 유지 = 결제 수면 약 (결제 수 x 1.1 + 20) 건
  PAYMENTS="$payments" POLL_MS="$poll" LIMIT_MS="$limit" PRODUCT_ID="$HOT_PRODUCT" \
    K6_SCRIPT=k6/scripts/stock-view-delay.js K6_PREPARE_SCRIPT=k6/scripts/payment-only-prepare.js FLASH_COUNT=1 DIAG_SAMPLERS=1 RAMP_S=0 HOLD_S="$payments" \
    $RUN step 1 "$D" > "$D/run.log" 2>&1 || die "step (tail: $(tail -3 "$D/run.log" | tr '\n' ' '))"
  sleep 6
  psql_seed -At -F $'\t' -c "SELECT stock FROM product WHERE id = '$HOT_PRODUCT'" > "$D/stock-after.txt"
  psql_seed -Atc "SELECT count(*) FROM stock_ledger WHERE product_id = '$HOT_PRODUCT' AND kind = 'SALE'" > "$D/ledger-sale-after.txt"
  psql_seed -Atc "SELECT count(*) FROM payment p JOIN orders o ON o.id = p.order_id JOIN order_item oi ON oi.order_id = o.id WHERE oi.product_id = '$HOT_PRODUCT' AND p.status = 'DONE'" > "$D/confirmed-after.txt"
  psql_seed -Atc "SELECT count(DISTINCT oi.product_id) FROM order_item oi WHERE oi.order_id IN ($(order_ids "$D/step-1/payonly-orders.json"))" > "$D/product-count.txt"
  capture_env
  write_meta "payments=$payments" "poll_ms=$poll" "limit_ms=$limit"
  summarize stockview
}

# ── 격벽 ────────────────────────────────────────────────────────────────
bulkhead_run() {
  local delay="${DELAY_MS:-5000}" vus="${VUS:-200}" hold="${HOLD_S:-30}"
  log "bulkhead delay=${delay}ms vus=$vus hold=$hold"
  reseed; boot
  wm_reset
  local stub
  stub="$(sed "s/__DELAY__/$delay/" k6/wiremock/bulkhead/confirm-delay.json | curl -fsS -X POST "$WM/__admin/mappings" -H 'Content-Type: application/json' -d @-)" || die "stub"
  local sid; sid="$(printf '%s' "$stub" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  echo "$sid" > "$D/stub-id.txt"
  # 준비 단계(주문 만들기)는 Toss 를 부르지 않는다 - 일지는 부하 직전에 한 번 더 비운다. 주문 수는 run.sh 가 rps 인자로 정한다(60 → 약 2,000건)
  MODE=vus VUS="$vus" K6_SCRIPT=k6/scripts/confirm-fixed.js K6_PREPARE_SCRIPT=k6/scripts/payment-only-prepare.js RAMP_S=0 HOLD_S="$hold" \
    $RUN step "${BULK_PREP_RPS:-60}" "$D" > "$D/run.log" 2>&1 || die "step (tail: $(tail -3 "$D/run.log" | tr '\n' ' '))"
  wm_journal "$D/wiremock-requests.json"
  curl -fsS -X DELETE "$WM/__admin/mappings/$sid" > /dev/null
  write_meta "toss_delay_ms=$delay" "vus=$vus" "hold_s=$hold"
  summarize bulkhead
}

# ── 속도 제한 ───────────────────────────────────────────────────────────
ratelimit_run() {
  local rate="${RATE:-100}" dur="${DURATION_S:-20}"
  log "ratelimit rate=$rate dur=$dur"
  reseed; boot
  local conf="$GEN/nginx.ratelimit.conf" ov="$GEN/compose.ratelimit.yml"
  # 운영 nginx/nginx.conf 의 17 · 22 · 39 번째 줄을 글자 그대로 옮긴다(추적하는 nginx/ 파일은 건드리지 않는다)
  sed -n '17p;22p;39p' nginx/nginx.conf > "$D/limit-lines.txt"
  python3 - "$NGINX_CONF" "$D/limit-lines.txt" "$conf" <<'PY'
import sys
src, lines, out = sys.argv[1:4]
zone, status, req = [l.rstrip("\n") for l in open(lines, encoding="utf-8")]
text = open(src, encoding="utf-8").read()
assert "http {" in text and "location /api/ {" in text, "unexpected loadtest nginx conf"
text = text.replace("http {", "http {\n" + zone + "\n" + status, 1)
text = text.replace("location /api/ {", "location /api/ {\n" + req, 1)
# 접속 주소를 로그 끝에 덧붙인다 - 한 주소에서 왔는지 확인하는 용도(앞 필드 위치는 그대로)
text = text.replace("$upstream_response_time';", "$upstream_response_time $remote_addr';", 1)
open(out, "w", encoding="utf-8").write(text)
PY
  printf 'services:\n  nginx:\n    volumes:\n      - %s:/etc/nginx/nginx.conf:ro\n' "$conf" > "$ov"
  cp "$conf" "$D/nginx.ratelimit.conf"
  $DC -f "$ov" up -d --no-build --force-recreate nginx > "$D/nginx-up.log" 2>&1 || die "nginx recreate"
  local w=0; until docker exec nginx nginx -t > /dev/null 2>&1 && curl -s -o /dev/null http://localhost/api/v1/product?size=1; do sleep 1; w=$((w + 1)); [ "$w" -lt 60 ] || die "nginx not up"; done
  write_condition "$D"
  sleep 10 # 확인 요청 몇 건이 채운 통을 비운다(초당 30 건씩 빠진다)
  local before; before="$(docker exec nginx sh -c "wc -l < $NGINX_LOG" | tr -d ' ')"
  start_samplers "$D"
  k6 run -q --no-usage-report -e RATE="$rate" -e DURATION_S="$dur" -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$D/k6-summary.json" k6/scripts/ratelimit-read.js > "$D/k6.log" 2>&1 || echo "k6 exit $?" >> "$D/k6.log"
  sleep 3
  stop_samplers
  docker exec nginx tail -n +"$((before + 1))" "$NGINX_LOG" > "$D/nginx.log"
  write_meta "rate=$rate" "duration_s=$dur"
  summarize ratelimit
}

# ── 결제 확정 뒤 부수효과 표본(outbox · 보상 실행 공통) ─────────────────────
run_payment_ids() { psql_seed -At -c "SELECT p.id FROM payment p WHERE p.order_id IN ($(order_ids "$1"))"; }
side_effects() { # 주문 파일 · 출력 파일(JSON)
  local ids; ids="$(order_ids "$1")"
  psql_seed -At -F $'\t' -c "
    SELECT 'payments_done', count(*) FROM payment WHERE order_id IN ($ids) AND status = 'DONE'
    UNION ALL SELECT 'orders_confirmed', count(*) FROM orders WHERE id IN ($ids) AND status = 'CONFIRMED'
    UNION ALL SELECT 'stock_ledger_rows', count(*) FROM stock_ledger WHERE payment_id IN (SELECT id FROM payment WHERE order_id IN ($ids))
    UNION ALL SELECT 'outbox_rows', count(*) FROM payment_outbox WHERE aggregate_id IN (SELECT id FROM payment WHERE order_id IN ($ids))
    UNION ALL SELECT 'product_stock_sum', coalesce(sum(stock), 0) FROM product WHERE id IN (SELECT product_id FROM order_item WHERE order_id IN ($ids))" \
    | python3 -c "import json,sys; print(json.dumps({l.split('\t')[0]: int(l.split('\t')[1]) for l in sys.stdin if l.strip()}))" > "$2"
}

outbox_run() {
  local rps="${N_RPS:-10}" hold="${HOLD_S:-10}"
  log "outbox-dup ${rps}/s x ${hold}s"
  reseed; boot
  K6_SCRIPT=k6/scripts/confirm-fixed.js K6_PREPARE_SCRIPT=k6/scripts/payment-only-prepare.js MODE=arrival TARGET_RPS="$rps" RAMP_S=0 HOLD_S="$hold" \
    $RUN step "$rps" "$D" > "$D/run.log" 2>&1 || die "step (tail: $(tail -3 "$D/run.log" | tr '\n' ' '))"
  local orders="$D/step-$rps/payonly-orders.json" ids; ids="$(order_ids "$orders")"
  # 모든 확정 결제가 DONE 이 되고 랙이 0 이 될 때까지
  local w=0
  until [ "$(psql_seed -Atc "SELECT count(*) FROM payment WHERE order_id IN ($ids) AND status IN ('READY','PENDING')")" = 0 ] && [ "$(lag_total)" = 0 ]; do sleep 2; w=$((w + 2)); [ "$w" -lt 180 ] || break; done
  sleep 5
  side_effects "$orders" "$D/side-effects-before.json"; describe_group > "$D/cg-before.txt"
  { docker logs "${PROJECT}-backend1-1" 2>&1 | grep -c 'PAYMENT_EVENT_CONSUMED' ; docker logs "${PROJECT}-backend1-1" 2>&1 | grep -c 'PAYMENT_APPROVED_SKIP'; } > "$D/log-counts-before.txt" || true
  psql_seed -At -F $'\t' -c "SELECT status, count(*) FROM payment_outbox WHERE aggregate_id IN (SELECT id FROM payment WHERE order_id IN ($ids)) GROUP BY 1" > "$D/outbox-status-before.tsv"
  # 그 결제들의 outbox 행을 PENDING 으로 되돌린다(앱의 READY 대사가 하는 republish() 와 같은 필드) - 실제 폴러가 다시 발행한다
  local n
  n="$(psql_seed -Atc "WITH u AS (UPDATE payment_outbox SET status = 'PENDING', published_at = NULL, republish_count = republish_count + 1
      WHERE aggregate_id IN (SELECT id FROM payment WHERE order_id IN ($ids)) AND status = 'PUBLISHED' RETURNING 1) SELECT count(*) FROM u")"
  echo "$n" > "$D/republished.txt"
  w=0
  until [ "$(psql_seed -Atc "SELECT count(*) FROM payment_outbox WHERE aggregate_id IN (SELECT id FROM payment WHERE order_id IN ($ids)) AND status = 'PENDING'")" = 0 ] && [ "$(lag_total)" = 0 ]; do sleep 2; w=$((w + 2)); [ "$w" -lt 180 ] || break; done
  echo "$w" > "$D/republish-wait-s.txt"
  sleep 8
  side_effects "$orders" "$D/side-effects-after.json"; describe_group > "$D/cg-after.txt"
  { docker logs "${PROJECT}-backend1-1" 2>&1 | grep -c 'PAYMENT_EVENT_CONSUMED' ; docker logs "${PROJECT}-backend1-1" 2>&1 | grep -c 'PAYMENT_APPROVED_SKIP'; } > "$D/log-counts-after.txt" || true
  psql_seed -At -F $'\t' -c "SELECT status, count(*) FROM payment_outbox WHERE aggregate_id IN (SELECT id FROM payment WHERE order_id IN ($ids)) GROUP BY 1" > "$D/outbox-status-after.tsv"
  write_meta "confirms=$((rps * hold))" "rps=$rps" "hold_s=$hold"
  summarize outbox
}

comp_run() {
  local rps="${N_RPS:-10}" hold="${HOLD_S:-20}" maxwait="${COMP_MAX_WAIT_S:-600}"
  log "comp-a ${rps}/s x ${hold}s, trigger on orders"
  prep() {
    docker exec -i db psql -U "$SEED_PG_USER" -d "$SEED_DB" -X -q -v ON_ERROR_STOP=1 <<'SQL'
CREATE OR REPLACE FUNCTION inj_order_confirm_fail() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.status = 'CONFIRMED' THEN RAISE EXCEPTION 'INJECTED_DB_FAILURE orders confirm'; END IF; RETURN NEW; END $$;
DROP TRIGGER IF EXISTS inj_order ON orders;
CREATE TRIGGER inj_order BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION inj_order_confirm_fail();
SQL
  }
  reseed prep; boot
  psql_seed -Atc "SELECT count(*) FROM pg_trigger WHERE tgname = 'inj_order'" > "$D/trigger-count.txt"
  wm_reset
  K6_SCRIPT=k6/scripts/confirm-fixed.js K6_PREPARE_SCRIPT=k6/scripts/payment-only-prepare.js MODE=arrival TARGET_RPS="$rps" RAMP_S=0 HOLD_S="$hold" \
    $RUN step "$rps" "$D" > "$D/run.log" 2>&1 || die "step (tail: $(tail -3 "$D/run.log" | tr '\n' ' '))"
  local orders="$D/step-$rps/payonly-orders.json" ids; ids="$(order_ids "$orders")"
  # 취소 호출 수가 보상 주기(30 초) 두 번 동안 변하지 않을 때까지(최대 maxwait 초)
  local t0 last=-1 same=0 now cur
  t0="$(date +%s)"
  : > "$D/cancel-count.tsv"
  while :; do
    cur="$(curl -fsS -X POST "$WM/__admin/requests/count" -H 'Content-Type: application/json' -d '{"method":"POST","urlPathPattern":"/v1/payments/.*/cancel"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])')"
    now="$(date +%s)"; printf '%s\t%s\n' "$((now - t0))" "$cur" >> "$D/cancel-count.tsv"
    if [ "$cur" = "$last" ]; then same=$((same + 15)); else same=0; fi
    last="$cur"
    [ "$same" -ge 60 ] && break
    [ $((now - t0)) -ge "$maxwait" ] && { echo "max wait reached" > "$D/comp-wait-note.txt"; break; }
    sleep 15
  done
  wm_journal "$D/wiremock-requests.json"
  psql_seed -At -F $'\t' -c "SELECT status, count(*) FROM payment WHERE order_id IN ($ids) GROUP BY 1 ORDER BY 1" > "$D/payment-states.tsv"
  psql_seed -At -F $'\t' -c "SELECT status, count(*) FROM orders WHERE id IN ($ids) GROUP BY 1 ORDER BY 1" > "$D/order-states.tsv"
  psql_seed -At -F $'\t' -c "SELECT status, count(*) FROM payment_compensation WHERE payment_id IN (SELECT id FROM payment WHERE order_id IN ($ids)) GROUP BY 1 ORDER BY 1" > "$D/compensation-states.tsv"
  write_meta "confirms=$((rps * hold))" "rps=$rps" "hold_s=$hold"
  summarize comp
}

stack_up
case "$RUNNAME" in
  coupon) coupon_run ;;
  hotrow) hotrow_run ;;
  bulkhead) bulkhead_run ;;
  ratelimit) ratelimit_run ;;
  outbox) outbox_run ;;
  comp) comp_run ;;
  stockview) stockview_run ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
echo "== $(date -u +%T) $RUNNAME done: $OUT/summary.json"
