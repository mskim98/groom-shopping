#!/usr/bin/env bash
# 3대 부하 측정 구동기 - 루트 compose 스택을 프로젝트 이름 r119-measure 로 띄우고 단계별로 재서 수집한다
# 사용: bash k6/loadtest/run.sh <명령> [인자]
#   keys                      secrets/jwt-keys.pem 이 없으면 만든다(저장소 밖 · .gitignore 의 secrets/ 대상)
#   infra                     db redis kafka wiremock 을 띄우고 준비될 때까지 기다린다
#   seed                      compose db 안에 시드 템플릿을 만들고 shop_seed_m1 로 복제 · pg_stat_statements 확장 · Redis 적재 · 토픽 재생성
#   app                       백엔드 이미지 빌드 후 backend1..3 · nginx 를 띄우고 건강 UP 을 기다린다
#   tiny <out-dir>            초당 50 요청 10 초 + 램프 2 초 (<out-dir>/tiny)
#   step <rps> <out-dir>      초당 <rps> 요청 램프 10 초 + 유지 30 초 (<out-dir>/step-<rps>)
#   collect <out-dir> <result.json>   단계 폴더를 result.json 한 개로 모은다(collect.py)
#   down                      스택의 컨테이너를 멈추고 지운다 - 볼륨은 지우지 않는다
# 지우는 것: down 이 이 프로젝트의 컨테이너와 네트워크만. 볼륨 · dev-* 컨테이너 · 다른 DB 는 건드리지 않는다. seed 는 shop_seed_template · shop_seed_m1 두 DB 를 다시 만든다
# 환경 변수(기본값): LAG_WAIT_S(120) 단계 뒤 소비자 랙이 0 이 되기를 기다리는 최대 초 · MAX_VUS(rps 별 아래 표) · RAMP_S(10) · HOLD_S(30)
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
  bash db/seed/build-template.sh
  bash db/seed/reset.sh "$SEED_DB"
  psql_seed -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"
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
  # 세 서비스가 같은 이미지 이름(groom-backend)을 쓰므로 한 번만 빌드한다
  $DC build backend1
  $DC up -d --no-build backend1 backend2 backend3 nginx
  for b in $BACKENDS; do wait_for "$b health" 300 backend_healthy "$b"; done
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

# 5 초마다 소비자 랙 · 컨테이너 자원 · 백엔드 Hikari 와 bulkhead 지표를 파일에 덧붙인다
sample_lag() {
  while :; do
    local out; out="$(kafka_cli kafka-consumer-groups --describe --group "$GROUP" 2>&1 || true)"
    printf '## %s\n%s\n' "$(date -u +%FT%TZ)" "$out" >> "$1/lag.log"
    sleep 5
  done
}
sample_stats() {
  while :; do
    local ts; ts="$(date -u +%FT%TZ)"
    docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' | sed "s/^/$ts\t/" >> "$1/stats.tsv"
    sleep 3
  done
}
sample_prom() {
  while :; do
    for b in $BACKENDS; do
      local ts; ts="$(date -u +%FT%TZ)"
      docker exec nginx curl -fsS "http://$b:8080/api/actuator/prometheus" 2> /dev/null \
        | grep -E '^(hikaricp_connections_(active|pending|max|timeout_total)|resilience4j_bulkhead_available_concurrent_calls|process_cpu_usage)[{ ]' \
        | sed "s/^/$ts\t$b\t/" >> "$1/prom.tsv" || true
    done
    sleep 5
  done
}

run_step() { # 이름 · 목표 요청률 · 최대 VU · 출력 폴더
  local name="$1" rps="$2" vus="$3" dir="$4"
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
  : > "$dir/lag.log"; : > "$dir/stats.tsv"; : > "$dir/prom.tsv"
  start="$(date -u +%FT%TZ)"
  echo "$start" > "$dir/start.txt"

  sample_lag "$dir" & local p1=$!
  sample_stats "$dir" & local p2=$!
  sample_prom "$dir" & local p3=$!
  trap 'kill $p1 $p2 $p3 2> /dev/null || true' EXIT

  echo "== $name: $rps requests/s, ramp ${RAMP_S}s + hold ${HOLD_S}s, max VUs $vus"
  # 종료 코드는 임계값 실패 등 k6 자체 판정이다 - 측정값은 요약 파일에서 읽으므로 여기서 멈추지 않는다
  k6 run -q --no-usage-report \
    -e TARGET_RPS="$rps" -e RAMP_S="$RAMP_S" -e HOLD_S="$HOLD_S" -e MAX_VUS="$vus" \
    -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$dir/k6-summary.json" \
    k6/scripts/flash-purchase-steps.js > "$dir/k6.log" 2>&1 || echo "k6 exit $?" >> "$dir/k6.log"
  end="$(date -u +%FT%TZ)"
  echo "$end" > "$dir/end.txt"

  # 랙이 빠질 때까지 표본을 계속 모은다(최대 LAG_WAIT_S 초)
  echo "lag_wait_after_s=$(wait_lag_zero)" >> "$dir/wait.txt"
  echo "lag_after=$(lag_total)" >> "$dir/wait.txt"
  sleep 6
  kill $p1 $p2 $p3 2> /dev/null || true
  wait $p1 $p2 $p3 2> /dev/null || true
  trap - EXIT

  end_offsets > "$dir/offsets_after.txt"
  psql_seed -At -F $'\t' -c "SELECT regexp_replace(left(query, 400), '\s+', ' ', 'g'), calls, round(mean_exec_time::numeric, 3), round(total_exec_time::numeric, 1)
    FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') ORDER BY total_exec_time DESC LIMIT 20" > "$dir/pgss.tsv"
  for b in $BACKENDS; do
    docker logs --since "$start" "${PROJECT}-$b-1" 2>&1 | grep 'STOCK_LEDGER_APPLY' > "$dir/ledger-$b.log" || true
    docker logs --since "$start" "${PROJECT}-$b-1" 2>&1 | grep -E ' (WARN|ERROR) ' | head -200 > "$dir/warn-$b.log" || true
  done
  sleep 1
  docker exec nginx tail -n +"$((before_lines + 1))" "$NGINX_LOG" > "$dir/nginx.log"
  echo "== $name done: $(tail -1 "$dir/k6.log")"
}

max_vus_for() { case "$1" in 2000) echo 1000 ;; 4000) echo 2000 ;; 6000) echo 3000 ;; *) echo $(( $1 / 2 + 1 )) ;; esac; }

# 시험 실행은 초당 50 요청 10 초(램프 2 초 뒤 유지 10 초) - 도구가 끝까지 도는지만 본다
cmd_tiny() { RAMP_S=2; HOLD_S=10; run_step tiny 50 "${MAX_VUS:-50}" "${1:?usage: tiny <out-dir>}/tiny"; }
cmd_step() { local rps="${1:?usage: step <rps> <out-dir>}" out="${2:?usage: step <rps> <out-dir>}"; run_step "step $rps" "$rps" "${MAX_VUS:-$(max_vus_for "$rps")}" "$out/step-$rps"; }
cmd_collect() { python3 "$ROOT/k6/loadtest/collect.py" "${1:?usage: collect <out-dir> <result.json>}" "${2:?usage: collect <out-dir> <result.json>}"; }
cmd_down() { $DC down --remove-orphans; }

sub="${1:-}"; shift || true
case "$sub" in
  keys|infra|seed|app|tiny|step|collect|down) "cmd_$sub" "$@" ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
