#!/usr/bin/env bash
# 부하 측정 구동기(백엔드 대수는 환경 변수 SERVERS, 기본 3) - 루트 compose 스택을 프로젝트 이름 r119-measure 로 띄우고 단계별로 재서 수집한다
# 사용: bash k6/loadtest/run.sh <명령> [인자]
# SERVERS 의 기본값은 3 이다 - 2대 스택에 거는 모든 명령(app · tiny · step · collect · down · check-env.sh · final-*.sh)은 SERVERS=2 를 붙여야 한다(안 붙이면 check-env 가 없는 세 번째 백엔드를 기다린다)
#   keys                      secrets/jwt-keys.pem 이 없으면 만든다(저장소 밖 · .gitignore 의 secrets/ 대상)
#   infra                     db redis kafka wiremock 을 띄우고 준비될 때까지 기다린다
#   seed                      시드 템플릿이 없을 때만 만들고(SEED_REBUILD_TEMPLATE=1 이면 다시) shop_seed_m1 로 복제 · pg_stat_statements 확장 · Redis 적재 · 토픽 재생성
#   app                       백엔드 이미지 빌드 후 SERVERS 대의 백엔드 · nginx 를 띄우고 건강 UP 을 기다린다
#   tiny <out-dir>            초당 50 요청 10 초 + 램프 2 초 (<out-dir>/tiny)
#   step <rps> <out-dir>      초당 <rps> 요청 램프 10 초 + 유지 30 초 (<out-dir>/step-<rps>)
#   collect <out-dir> <result.json>   단계 폴더를 result.json 한 개로 모은다(collect.py)
#   down                      스택의 컨테이너를 멈추고 지운다 - 볼륨은 지우지 않는다
# tiny · step 은 <out-dir> 자체에 진단 파일도 쓴다(같은 <out-dir> 로 두 단계를 돌리면 뒤의 단계가 덮어쓴다): pg-activity.tsv(pg_stat_activity 1 초 표본) ·
#   backend<N>.log(단계 구간의 백엔드 로그 전체) · leak-digest.txt · pg-digest.txt(leak_digest.py · pg_activity_digest.py 의 요약)
#   THREAD_DUMP_EVERY_S 가 0 보다 크면 유지 구간 동안 그 간격(초)마다 백엔드마다 SIGQUIT 를 보내 JVM 이 컨테이너 로그에 찍은 스레드 덤프를 threads-backend<N>.txt 로 자르고 thread-digest.txt 를 쓴다(thread_digest.py)
# 지우는 것: down 이 이 프로젝트의 컨테이너와 네트워크만. 볼륨 · dev-* 컨테이너 · 다른 DB 는 건드리지 않는다. seed 는 shop_seed_template · shop_seed_m1 두 DB 를 다시 만든다
# 환경 변수(기본값): SERVERS(3) 백엔드 대수 · APP_SETTLE_S(20) app 이 건강 UP 뒤 내려간 백엔드를 다시 보기 전에 쉬는 초 · LAG_WAIT_S(120) 단계 뒤 소비자 랙이 0 이 되기를 기다리는 최대 초 · MAX_VUS(rps 별 아래 표) · RAMP_S(10) · HOLD_S(30) · TINY_HOLD_S(10) tiny 의 유지 초 · THREAD_DUMP_EVERY_S(0) 스레드 덤프 간격 초, 0 이면 끔 · LEAK_THRESHOLD_MS(2000) leak_digest 가 보고 시각에 더하는 문턱값(compose 의 SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD 와 같아야 한다)
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$ROOT"

RAMP_S="${RAMP_S:-10}"
HOLD_S="${HOLD_S:-30}"
LAG_WAIT_S="${LAG_WAIT_S:-120}"
GROUP="payment-event-group"

kafka_cli() { local cli="$1"; shift; docker exec kafka "$cli" --bootstrap-server localhost:9093 "$@"; }

wait_for() { # 설명 · 최대 초 · 명령…
  local what="$1" max="$2"; shift 2
  local waited=0
  until "$@" > /dev/null 2>&1; do
    [ "$waited" -lt "$max" ] || { echo "timeout waiting for $what (${max}s)" >&2; return 1; }
    sleep 2; waited=$((waited + 2))
  done
}

cmd_keys() {
  if [ -f secrets/jwt-keys.pem ]; then echo "secrets/jwt-keys.pem exists"; return; fi
  mkdir -p secrets
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/priv.pem \
    && { cat secrets/priv.pem; openssl pkey -in secrets/priv.pem -pubout; } > secrets/jwt-keys.pem && rm secrets/priv.pem
  # 컨테이너 사용자(uid 10001)가 읽어야 한다 - 이 컴퓨터에서만 쓰는 측정용 키다
  chmod 644 secrets/jwt-keys.pem
  echo "created secrets/jwt-keys.pem"
}

cmd_infra() {
  $DC up -d db redis kafka wiremock
  wait_for "postgres" 120 docker exec db pg_isready -U "$SEED_PG_USER"
  wait_for "redis" 60 docker exec redis redis-cli ping
  wait_for "kafka" 120 kafka_cli kafka-topics --list
  wait_for "wiremock" 60 curl -fsS http://localhost:18089/__admin/mappings
  echo "infra up"
}

cmd_seed() {
  # 템플릿은 한 번 만들고 복제한다 - 이미 템플릿 표시가 된 DB 가 있으면 다시 만들지 않는다(새 Flyway 마이그레이션이 생겼을 때만 SEED_REBUILD_TEMPLATE=1)
  if [ "${SEED_REBUILD_TEMPLATE:-0}" = 1 ] || [ "$(psql_db postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = 'shop_seed_template' AND datistemplate")" != 1 ]; then
    bash db/seed/build-template.sh
  else
    echo "== shop_seed_template exists, reused"
  fi
  bash db/seed/reset.sh "$SEED_DB"
  date -u +%FT%TZ > "$SEED_STAMP"
  psql_seed -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"
  # 백엔드가 멈춘 상태의 측정용 Redis 를 비운다 - 앞 시험이 남긴 장바구니 키(cart:<사용자 id>)가 남으면 GET /cart 가 DB 에 없는 옛 항목을 보여 주고
  # 시드가 DB 에 넣어 둔 장바구니 항목은 비워지지 않아 첫 주문에 섞인다. load-redis.sh 는 자기 소유 키 패턴만 지우므로 여기서 한다
  docker exec redis redis-cli FLUSHALL > /dev/null
  bash db/seed/load-redis.sh "$SEED_DB"
  bash db/seed/recreate-topics.sh
  # 백엔드가 뜨기 전의 검사 - 기동 뒤에는 시드의 PENDING 행이 처리돼 이 검사가 통과하지 못한다(R119-7)
  local code=0 out
  out="$(bash db/seed/verify.sh "$SEED_DB" 1)" || code=$?
  echo "verify.sh $SEED_DB 1 before backends boot: exit $code, PASS $(printf '%s\n' "$out" | grep -c '^PASS '), FAIL $(printf '%s\n' "$out" | grep -c '^FAIL ')"
  [ "$code" -eq 0 ] || { printf '%s\n' "$out" | grep '^FAIL ' >&2; return 1; }
}

backend_healthy() { docker exec nginx curl -fsS "http://$1:8080/api/actuator/health" | grep -q '"status":"UP"'; }

cmd_app() {
  # 백엔드 서비스는 같은 이미지 이름(groom-backend)을 쓰므로 한 번만 빌드한다
  $DC build "${BACKENDS%% *}"
  $DC up -d --no-build $BACKENDS nginx
  for b in $BACKENDS; do wait_for "$b health" 300 backend_healthy "$b"; done
  # 기동 직후 한 대가 Redis 연결 전에 러너가 실패해 스스로 내려간 적이 있다(CouponRedisKeyMigrationRunner) - 20 초 뒤 다시 보고 내려간 대는 한 번 다시 띄운다
  sleep "${APP_SETTLE_S:-20}"
  for b in $BACKENDS; do
    if ! backend_healthy "$b"; then
      echo "$b is not healthy after boot, starting it once more" >&2
      $DC up -d --no-build "$b"
      wait_for "$b health" 300 backend_healthy "$b"
    fi
  done
  echo "app up"
}

# payment-events 의 파티션별 끝 오프셋 "partition offset" 12 줄
end_offsets() {
  kafka_cli kafka-get-offsets --topic payment-events --time -1 2> /dev/null | awk -F: '{print $2, $3}' | sort -n
}

# payment-event-group 의 payment-events 랙 합계
lag_total() {
  kafka_cli kafka-consumer-groups --describe --group "$GROUP" 2> /dev/null | awk '$2 == "payment-events" && $6 ~ /^[0-9]+$/ {s += $6} END {print s + 0}'
}

wait_lag_zero() { # 기다린 초를 표준 출력으로
  local waited=0
  while [ "$(lag_total)" != 0 ] && [ "$waited" -lt "$LAG_WAIT_S" ]; do sleep 2; waited=$((waited + 2)); done
  echo "$waited"
}

# 한 표본을 뜨는 데 걸린 시간(kafka-consumer-groups · docker stats 는 2 초 안팎)을 빼고 쉰다 - 그냥 sleep 5 면 표본 간격이 7 초가 된다
nap() { local d=$(( 5 - ($(date +%s) - $1) )); [ "$d" -gt 0 ] && sleep "$d"; return 0; }

# 5 초마다 소비자 랙 · 컨테이너 자원 · 백엔드 Hikari 와 bulkhead 지표를 파일에 덧붙인다
sample_lag() {
  while :; do
    local t0; t0="$(date +%s)"
    local out; out="$(kafka_cli kafka-consumer-groups --describe --group "$GROUP" 2>&1 || true)"
    printf '## %s\n%s\n' "$(date -u +%FT%TZ)" "$out" >> "$1/lag.log"
    nap "$t0"
  done
}
sample_stats() {
  while :; do
    local t0; t0="$(date +%s)"
    local ts; ts="$(date -u +%FT%TZ)"
    docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' | sed "s/^/$ts\t/" >> "$1/stats.tsv"
    nap "$t0"
  done
}
sample_prom() {
  while :; do
    local t0; t0="$(date +%s)"
    for b in $BACKENDS; do
      local ts; ts="$(date -u +%FT%TZ)"
      docker exec nginx curl -fsS "http://$b:8080/api/actuator/prometheus" 2> /dev/null \
        | grep -E '^(hikaricp_connections_(active|pending|max|timeout_total)|hikaricp_connections_(usage|acquire)_seconds_(count|sum|max)|resilience4j_bulkhead_available_concurrent_calls|process_cpu_usage)[{ ]' \
        | sed "s/^/$ts\t$b\t/" >> "$1/prom.tsv" || true
    done
    nap "$t0"
  done
}

# 유지 구간의 시작과 끝에 확정 구간 타이머(payment_confirm_stage_seconds)를 백엔드마다 한 번씩 읽는다 - 유지 구간은 k6 준비 단계(setup)가 끝난 줄 + 램프 초부터 유지 초 동안
# 두 표본의 차이가 유지 구간의 구간별 건수 · 합이다(collect.py 의 stage_delta). 시각은 초 단위이고 k6 와 같은 시계를 쓰므로 오차는 1~2 초
sample_confirm_stages() { # 폴더 · 램프 초 · 유지 초 · k6 로그
  local waited=0
  until grep -q 'setup:' "$4" 2> /dev/null || [ "$waited" -ge 120 ]; do sleep 1; waited=$((waited + 1)); done
  sleep "$2"
  scrape_metric '^payment_confirm_stage_seconds_(count|sum)[{]' "$1/confirm-stages-start.tsv"
  sleep "$3"
  scrape_metric '^payment_confirm_stage_seconds_(count|sum)[{]' "$1/confirm-stages-end.tsv"
}

# pg_stat_activity 표본(진단용) - 측정 DB 의 유휴가 아닌 세션(유휴라도 xact_start 가 찍힌 것은 포함)을 1 초마다 읽는다. 컨테이너 안에서 psql 연결 하나를 열어 두고 \watch 1 로 같은 문장을 되풀이한다
# (매번 docker exec · psql 을 새로 치면 CPU 가 찬 때 표본 간격이 1.3 초 넘게 늘어난다 - 첫 시험에서 중앙값 1.32 초 · 최대 4.6 초). 표본 하나의 비용은
# pg_stat_activity 한 번 읽기(수 ms)와 측정 DB 에 붙은 연결 1 개이고, 활동 행이 없는 시각에도 빈 행 하나를 남겨 표본 시각이 끊기지 않게 한다
# 연결은 application_name r120-pg-sample 로 구분하고 stop_pg_sample 이 pg_terminate_backend 로 끊는다(docker exec 를 죽여도 컨테이너 안 psql 은 남는다)
PG_SAMPLE_SQL="WITH n AS (SELECT statement_timestamp() AS t)
SELECT to_char(n.t AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), coalesce(a.pid::text, ''), coalesce(a.state, ''), coalesce(a.wait_event_type, ''), coalesce(a.wait_event, ''),
  coalesce(round(extract(epoch FROM n.t - a.xact_start) * 1000)::bigint::text, ''), coalesce(round(extract(epoch FROM n.t - a.query_start) * 1000)::bigint::text, ''),
  CASE WHEN a.pid IS NULL THEN '' ELSE pg_blocking_pids(a.pid)::text END, coalesce(left(regexp_replace(a.query, '\\s+', ' ', 'g'), 200), '')
FROM n LEFT JOIN pg_stat_activity a ON a.datname = current_database() AND a.pid <> pg_backend_pid() AND a.backend_type = 'client backend' AND (a.state IS DISTINCT FROM 'idle' OR a.xact_start IS NOT NULL)"

sample_pg() { # 출력 폴더
  printf 'sample_time\tpid\tstate\twait_event_type\twait_event\txact_age_ms\tquery_age_ms\tblocking_pids\tquery\n' > "$1/pg-activity.tsv"
  docker exec -e SQL="$PG_SAMPLE_SQL" -e PGPASSWORD="$SEED_PG_PASSWORD" -e PGAPPNAME=r120-pg-sample db sh -c '
    printf "%s\n\\\\watch i=1\n" "$SQL" | psql -U "'"$SEED_PG_USER"'" -d '"$SEED_DB"' -X -q -At -F "$(printf "\t")"' >> "$1/pg-activity.tsv" 2> /dev/null || true
}
stop_pg_sample() { psql_db postgres -Atc "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'r120-pg-sample'" > /dev/null 2>&1 || true; }

# 유지 구간 동안 every 초마다 백엔드마다 SIGQUIT 를 보낸다 - JVM 이 멈추지 않고 전체 스레드 덤프를 표준 출력(컨테이너 로그)에 찍는다
# k6 의 준비 단계(setup, 로그인 천 건)가 끝난 줄이 k6 로그에 찍힌 뒤 램프 초를 기다려 유지 구간에 맞춘다
# 부하 중에는 docker kill 한 번이 2 초 넘게 걸려(첫 실행: 덤프 간격 3~4 초, 백엔드당 15 장) 보내는 일을 기다리지 않고 시각표(시작 + n x every 초)대로 백그라운드로 보낸다
sample_threads() { # 간격 초 · 램프 초 · 유지 초 · k6 로그
  local every="$1" t0 end n=0 d b waited=0
  until grep -q 'setup:' "$4" 2> /dev/null || [ "$waited" -ge 120 ]; do sleep 1; waited=$((waited + 1)); done
  sleep "$2"
  t0="$(date +%s)"; end=$((t0 + $3))
  while [ "$(date +%s)" -lt "$end" ]; do
    for b in $BACKENDS; do docker kill --signal=QUIT "${PROJECT}-$b-1" > /dev/null 2>&1 & done
    n=$((n + 1)); d=$((t0 + n * every - $(date +%s)))
    [ "$d" -gt 0 ] && sleep "$d"
  done
  wait || true
  return 0
}

# 컨테이너 로그를 따라가며 스레드 덤프만 파일에 쓴다 - `Full thread dump` 줄부터 `JNI global refs` 줄까지(그 뒤의 Heap 요약은 버린다)
# 단계가 끝난 뒤 docker logs 로 자르지 않는 이유: Docker 로그 드라이버(local)는 컨테이너당 100 MB(20 MB x 5)만 남겨 덤프 한 장이 4 MB 넘는 부하 중에는 최근 15 장만 남는다(첫 두 실행)
follow_thread_dumps() { # 시작 시각 · 컨테이너 · 출력 파일
  docker logs -f --since "$1" "$2" 2>&1 | awk '/Full thread dump/ {on = 1} on {print} /^JNI global refs/ {on = 0}' > "$3"
}
stop_follow_thread_dumps() { pkill -f "docker logs -f --since .* ${PROJECT}-" 2> /dev/null || true; sleep 1; }

# 5 초마다 k6 프로세스의 CPU 사용률(%cpu, 코어 하나 = 100)을 호스트에서 읽는다 - 부하 생성기가 한계였는지 가리는 근거
sample_k6_cpu() { # 폴더 · k6 pid
  while kill -0 "$2" 2> /dev/null; do
    local t0; t0="$(date +%s)"
    printf '%s\t%s\n' "$(date -u +%FT%TZ)" "$(ps -o %cpu= -p "$2" | tr -d ' ')" >> "$1/k6cpu.tsv"
    nap "$t0"
  done
}

# 시험 창(DB 시계) - 주문 시각은 앱이 Asia/Seoul 시각으로 timestamp 열에 쓰므로 같은 기준으로 읽는다
db_now() { psql_seed -Atc "SELECT to_char(now() AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS.US')"; }

run_step() { # 이름 · 목표 요청률 · 최대 VU · 단계 폴더 · 출력 폴더(진단 파일이 가는 곳)
  local name="$1" rps="$2" vus="$3" dir="$4" out="$5"
  mkdir -p "$dir"
  local before_lines start end
  # 단계 전에 랙이 남아 있으면 앞 단계의 일이 섞인다
  echo "lag_wait_before_s=$(wait_lag_zero)" > "$dir/wait.txt"
  for b in $BACKENDS; do
    echo "$b $(docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJECT}_app-network\").IPAddress}}" "${PROJECT}-$b-1")"
  done > "$dir/backends.tsv"
  psql_seed -c "SELECT pg_stat_statements_reset()" > /dev/null
  end_offsets > "$dir/offsets_before.txt"
  before_lines="$(docker exec nginx sh -c "wc -l < $NGINX_LOG" 2> /dev/null | tr -d ' ' || true)"
  before_lines="${before_lines:-0}"
  echo "$before_lines" > "$dir/nginx_lines_before.txt"
  : > "$dir/lag.log"; : > "$dir/stats.tsv"; : > "$dir/prom.tsv"; : > "$dir/k6cpu.tsv"; : > "$dir/k6.log"
  db_now > "$dir/db_window_start.txt"
  start="$(date -u +%FT%TZ)"
  echo "$start" > "$dir/start.txt"

  sample_lag "$dir" & local p1=$!
  sample_stats "$dir" & local p2=$!
  sample_prom "$dir" & local p3=$!
  sample_confirm_stages "$dir" "$RAMP_S" "$HOLD_S" "$dir/k6.log" & local p7=$!
  # 진단 표본(pg_stat_activity 1 초)은 요청마다 DB 에 연결 하나를 더 쓰므로 DIAG_SAMPLERS=1 일 때만 돈다 - 최종 측정은 끈다
  local p5=""
  if [ "${DIAG_SAMPLERS:-0}" = 1 ]; then sample_pg "$out" & p5=$!; fi
  local p6=""
  if [ "${THREAD_DUMP_EVERY_S:-0}" -gt 0 ]; then
    for b in $BACKENDS; do follow_thread_dumps "$start" "${PROJECT}-$b-1" "$out/threads-$b.txt" & done
    sample_threads "$THREAD_DUMP_EVERY_S" "$RAMP_S" "$HOLD_S" "$dir/k6.log" & p6=$!
  fi
  trap 'stop_pg_sample; kill $p1 $p2 $p3 $p6 $p7 2> /dev/null || true' EXIT

  write_condition "$dir" "$HOLD_S" "$RAMP_S"
  echo "== $name: $rps requests/s, ramp ${RAMP_S}s + hold ${HOLD_S}s, max VUs $vus"
  # 종료 코드는 임계값 실패 등 k6 자체 판정이다 - 측정값은 요약 파일에서 읽으므로 여기서 멈추지 않는다
  k6 run -q --no-usage-report \
    -e TARGET_RPS="$rps" -e RAMP_S="$RAMP_S" -e HOLD_S="$HOLD_S" -e MAX_VUS="$vus" \
    -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$dir/k6-summary.json" \
    k6/scripts/flash-purchase-steps.js > "$dir/k6.log" 2>&1 &
  local kpid=$!
  sample_k6_cpu "$dir" "$kpid" & local p4=$!
  wait "$kpid" || echo "k6 exit $?" >> "$dir/k6.log"
  wait "$p4" 2> /dev/null || true
  wait "$p7" 2> /dev/null || true # 유지 구간 끝 표본이 파일에 쓰일 때까지(kill 하지 않는다)
  db_now > "$dir/db_window_end.txt"
  end="$(date -u +%FT%TZ)"
  echo "$end" > "$dir/end.txt"

  # 랙이 빠질 때까지 표본을 계속 모은다(최대 LAG_WAIT_S 초)
  echo "lag_wait_after_s=$(wait_lag_zero)" >> "$dir/wait.txt"
  echo "lag_after=$(lag_total)" >> "$dir/wait.txt"
  sleep 6
  stop_follow_thread_dumps
  stop_pg_sample
  kill $p1 $p2 $p3 $p6 2> /dev/null || true
  wait $p1 $p2 $p3 $p5 $p6 2> /dev/null || true
  trap - EXIT

  end_offsets > "$dir/offsets_after.txt"
  psql_seed -At -F $'\t' -c "SELECT regexp_replace(left(query, 400), '\s+', ' ', 'g'), calls, round(mean_exec_time::numeric, 3), round(total_exec_time::numeric, 1)
    FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') ORDER BY total_exec_time DESC LIMIT 20" > "$dir/pgss.tsv"
  for b in $BACKENDS; do
    docker logs --since "$start" "${PROJECT}-$b-1" 2>&1 | grep 'STOCK_LEDGER_APPLY' > "$dir/ledger-$b.log" || true
    docker logs --since "$start" "${PROJECT}-$b-1" 2>&1 | grep -E ' (WARN|ERROR) ' | head -200 > "$dir/warn-$b.log" || true
  done
  # 진단 파일 - 단계 구간의 백엔드 로그 전체(Hikari 누수 감지 보고 포함)와 두 요약
  for b in $BACKENDS; do docker logs --since "$start" "${PROJECT}-$b-1" > "$out/$b.log" 2>&1 || true; done
  python3 -B k6/loadtest/leak_digest.py --threshold-ms "${LEAK_THRESHOLD_MS:-2000}" "$out"/backend*.log > "$out/leak-digest.txt" || true
  python3 -B k6/loadtest/pg_activity_digest.py "$out/pg-activity.tsv" > "$out/pg-digest.txt" || true
  if [ "${THREAD_DUMP_EVERY_S:-0}" -gt 0 ]; then
    python3 -B k6/loadtest/thread_digest.py "$out"/threads-backend*.txt > "$out/thread-digest.txt" || true
  fi
  sleep 1
  docker exec nginx tail -n +"$((before_lines + 1))" "$NGINX_LOG" > "$dir/nginx.log"
  echo "== $name done: $(tail -1 "$dir/k6.log")"
}

max_vus_for() { case "$1" in 2000) echo 1000 ;; 4000) echo 2000 ;; 6000) echo 3000 ;; *) echo $(( $1 / 2 + 1 )) ;; esac; }

# 시험 실행은 초당 50 요청 10 초(램프 2 초 뒤 유지 10 초) - 도구가 끝까지 도는지만 본다
cmd_tiny() { RAMP_S=2; HOLD_S="${TINY_HOLD_S:-10}"; local out="${1:?usage: tiny <out-dir>}"; mkdir -p "$out"; run_step tiny 50 "${MAX_VUS:-50}" "$out/tiny" "$out"; }
cmd_step() { local rps="${1:?usage: step <rps> <out-dir>}" out="${2:?usage: step <rps> <out-dir>}"; mkdir -p "$out"; run_step "step $rps" "$rps" "${MAX_VUS:-$(max_vus_for "$rps")}" "$out/step-$rps" "$out"; }
cmd_collect() { python3 -B "$ROOT/k6/loadtest/collect.py" "${1:?usage: collect <out-dir> <result.json>}" "${2:?usage: collect <out-dir> <result.json>}"; }
cmd_down() { $DC down --remove-orphans; }

sub="${1:-}"; shift || true
case "$sub" in
  keys|infra|seed|app|tiny|step|collect|down) "cmd_$sub" "$@" ;;
  *) sed -n '2,16p' "$0"; exit 2 ;;
esac
