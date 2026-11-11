# run.sh · check-env.sh · final-*.sh 가 함께 쓰는 환경 - 직접 실행하지 않고 `. env.sh` 로 불러 쓴다
# SERVERS(환경 변수, 기본 3) = 백엔드 대수. BACKENDS · nginx upstream · compose 의존 · 중지 도우미 · 조건 기록이 모두 이 값 하나를 따른다
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT="r119-measure"
SEED_DB="shop_seed_m1"
NGINX_LOG="/var/log/nginx/loadtest.access.log"
SEED_STAMP="${SEED_STAMP:-/tmp/r119-measure-seed-stamp}"
# 루트 compose 가 정의한 백엔드 서비스 수 - 이보다 많은 대수는 compose 가 모른다
MAX_SERVERS=3
SERVERS="${SERVERS-3}"
case "$SERVERS" in
  [1-9]) [ "$SERVERS" -le "$MAX_SERVERS" ] || { echo "SERVERS=$SERVERS: 1..$MAX_SERVERS 만 쓸 수 있다" >&2; return 1 2> /dev/null || exit 1; } ;;
  *) echo "SERVERS='$SERVERS': 1..$MAX_SERVERS 의 정수여야 한다" >&2; return 1 2> /dev/null || exit 1 ;;
esac
BACKENDS=""
for ((i = 1; i <= SERVERS; i++)); do BACKENDS="${BACKENDS:+$BACKENDS }backend$i"; done

# 이 대수의 nginx 설정(k6/loadtest/.generated/ - k6/ 는 .gitignore 대상)과 compose 덧씌우기를 만든다
# nginx 설정은 nginx/nginx.loadtest.conf(세 대 기준)에서 집합 밖 백엔드의 upstream 줄만 지운 것, 덧씌우기는 nginx 가 집합의 백엔드에만 의존하게 하고(프런트엔드 제외) 그 설정을 마운트한다
GEN="$ROOT/k6/loadtest/.generated"
NGINX_CONF="$GEN/nginx.loadtest.$SERVERS.conf"
render_servers() {
  local ov="$GEN/compose.servers$SERVERS.yml" b i
  mkdir -p "$GEN"
  cp "$ROOT/nginx/nginx.loadtest.conf" "$NGINX_CONF"
  for ((i = SERVERS + 1; i <= MAX_SERVERS; i++)); do sed -i.bak "/server backend$i:8080;/d" "$NGINX_CONF"; done
  rm -f "$NGINX_CONF.bak"
  {
    echo "services:"
    echo "  nginx:"
    echo "    volumes:"
    echo "      - $NGINX_CONF:/etc/nginx/nginx.conf:ro"
    echo "    depends_on: !override"
    for b in $BACKENDS; do echo "      - $b"; done
  } > "$ov"
  DC="docker compose -p $PROJECT -f $ROOT/docker-compose.yml -f $ROOT/k6/loadtest/docker-compose.loadtest.yml -f $ov"
}
render_servers

# 집합의 백엔드마다 /actuator/prometheus 에서 정규식에 맞는 줄을 "<시각>\t<백엔드>\t<줄>" 로 파일에 덧붙인다 - 확정 구간 · 쿠폰 단계 타이머를 시험 구간의 시작과 끝에 읽는 용도(스택이 내려가 있으면 아무것도 쓰지 않는다)
scrape_metric() { # 정규식 · 파일
  local b ts
  for b in $BACKENDS; do
    ts="$(date -u +%FT%TZ)"
    docker exec nginx curl -fsS "http://$b:8080/api/actuator/prometheus" 2> /dev/null | grep -E "$1" | sed "s/^/$ts\t$b\t/" >> "$2" || true
  done
}

# 이 프로젝트의 nginx 와 집합의 백엔드 컨테이너를 멈춘다 - 시드 복제 전에 DB 연결을 끊어 두는 용도
stop_backends() {
  local b names=""
  for b in $BACKENDS; do names="$names ${PROJECT}-$b-1"; done
  docker stop nginx $names > /dev/null 2>&1 || true
}

# 떠 있는 백엔드 컨테이너 수 - 조건 기록의 servers (집합이 아니라 Docker 가 보는 값)
running_servers() { docker ps --format '{{.Names}}' 2> /dev/null | grep -cE "^${PROJECT}-backend[0-9]+-1$" || true; }

# 이 단계 · 시나리오가 어떤 조건에서 돌았는지 폴더에 남긴다 - 스레드 종류 · 누수 감지 문턱값은 집합의 첫 백엔드 컨테이너의 환경에서, 시드 복제 시각은 run.sh seed 가 남긴 SEED_STAMP 에서,
# servers 는 떠 있는 백엔드 컨테이너 수에서 읽는다. 인자: 폴더 [유지 초 램프 초] - 유지 · 램프 초는 플래시 단계만 준다
write_condition() {
  local vt lk extra=""
  vt="$(docker exec "${PROJECT}-${BACKENDS%% *}-1" printenv SPRING_THREADS_VIRTUAL_ENABLED 2> /dev/null || echo true)"
  lk="$(docker exec "${PROJECT}-${BACKENDS%% *}-1" printenv SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD 2> /dev/null || echo 0)"
  [ -n "${2:-}" ] && extra=", \"hold_s\": $2, \"ramp_s\": ${3:?ramp seconds}"
  printf '{"threads": "%s", "leak_detection": %s, "diag_samplers": %s, "seed_copy": "%s", "servers": %s%s}\n' \
    "$([ "$vt" = false ] && echo platform || echo virtual)" "$([ "${lk:-0}" -gt 0 ] && echo true || echo false)" \
    "$([ "${DIAG_SAMPLERS:-0}" = 1 ] && echo true || echo false)" "$(cat "$SEED_STAMP" 2> /dev/null || echo unknown)" "$(running_servers)" "$extra" > "$1/condition.json"
}

# .env 의 값 하나(비밀번호를 셸 명령에 풀어 쓰지 않으려고 source 하지 않고 한 줄만 읽는다)
envval() { grep -E "^$1=" "$ROOT/.env" 2> /dev/null | head -1 | cut -d= -f2-; }

# 시드 스크립트(db/seed)가 compose 의 db · redis · kafka 컨테이너를 보게 한다
export SEED_PG_CONTAINER=db
export SEED_PG_USER="$(envval POSTGRES_USER)"
export SEED_PG_PASSWORD="$(envval POSTGRES_PASSWORD)"
export SEED_KAFKA_CONTAINER=kafka
export SEED_REDIS_PORT=6379
export SEED_REDIS_CONTAINER=redis

psql_seed() { docker exec db psql -U "$SEED_PG_USER" -d "$SEED_DB" -X -q -v ON_ERROR_STOP=1 "$@"; }
psql_db() { local db="$1"; shift; docker exec db psql -U "$SEED_PG_USER" -d "$db" -X -q -v ON_ERROR_STOP=1 "$@"; }
