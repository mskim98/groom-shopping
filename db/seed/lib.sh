#!/usr/bin/env bash
# 시드 스크립트 공용 함수 — 직접 실행하지 않고 `. lib.sh` 로 불러 쓴다
# 환경 변수(기본값):
#   SEED_PG_CONTAINER   PostgreSQL 컨테이너 이름 (dev-db) — psql 은 이 컨테이너 안에서 실행한다
#   SEED_PG_USER        PostgreSQL 사용자 (dev) · SEED_PG_PASSWORD 비밀번호 (dev123, docker-compose.dev.yml 의 개발용 값)
#   SEED_TEMPLATE_DB    템플릿 DB 이름 (shop_seed_template)
#   SEED_REDIS_PORT     Redis 포트 (6379) · SEED_REDIS_CONTAINER 로 컨테이너를 직접 지정할 수 있다(기본: 그 포트를 게시한 컨테이너)
#   SEED_KAFKA_CONTAINER Kafka 컨테이너 이름 (dev-kafka) — 컨테이너 안의 내부 리스너 localhost:9093 을 쓴다
# 지우는 것: seed_drop_db 는 이름이 shop_seed 로 시작하는 DB 하나만 지운다. 그 밖의 이름은 exit 2 로 거절한다
set -euo pipefail

SEED_PG_CONTAINER="${SEED_PG_CONTAINER:-dev-db}"
SEED_PG_USER="${SEED_PG_USER:-dev}"
SEED_PG_PASSWORD="${SEED_PG_PASSWORD:-dev123}"
SEED_TEMPLATE_DB="${SEED_TEMPLATE_DB:-shop_seed_template}"
SEED_REDIS_PORT="${SEED_REDIS_PORT:-6379}"
SEED_KAFKA_CONTAINER="${SEED_KAFKA_CONTAINER:-dev-kafka}"

# 시각 초(소수 한 자리)
seed_now() { perl -MTime::HiRes=time -e 'printf "%.1f", time'; }

# 이름이 shop_seed + 영숫자·밑줄 만이 아니면 exit 2
seed_check_name() {
  case "$1" in
    shop_seed*) ;;
    *) echo "refused: database name '$1' does not start with shop_seed" >&2; return 2 ;;
  esac
  if ! printf '%s' "$1" | grep -Eq '^shop_seed[a-z0-9_]*$'; then
    echo "refused: database name '$1' has characters other than a-z 0-9 _" >&2
    return 2
  fi
}

# seed_psql <db> [psql 인자…] — 표준 입력을 건드리지 않는다(반복문 안에서 써도 안전)
seed_psql() {
  local db="$1"; shift
  docker exec "$SEED_PG_CONTAINER" psql -U "$SEED_PG_USER" -d "$db" -X -q -v ON_ERROR_STOP=1 "$@"
}

# seed_psql_stdin <db> [psql 인자…] — 표준 입력을 컨테이너의 psql 로 넘긴다
seed_psql_stdin() {
  local db="$1"; shift
  docker exec -i "$SEED_PG_CONTAINER" psql -U "$SEED_PG_USER" -d "$db" -X -q -v ON_ERROR_STOP=1 "$@"
}

# seed_drop_db <db> — 세션을 끊고 템플릿 표시를 풀어 지운다. 없으면 아무것도 안 한다
seed_drop_db() {
  seed_check_name "$1" || return $?
  [ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$1'")" = 1 ] || return 0
  seed_psql postgres -c "ALTER DATABASE $1 IS_TEMPLATE false" > /dev/null
  seed_psql postgres -Atc "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$1' AND pid <> pg_backend_pid()" > /dev/null
  seed_psql postgres -c "DROP DATABASE IF EXISTS $1" > /dev/null
}

# seed_rcli <redis-cli 인자…> — SEED_REDIS_PORT 를 게시한 컨테이너 안의 redis-cli. 표준 입력을 건드리지 않는다
seed_redis_container() {
  if [ -n "${SEED_REDIS_CONTAINER:-}" ]; then printf '%s' "$SEED_REDIS_CONTAINER"; return; fi
  local c
  c="$(docker ps --filter "publish=$SEED_REDIS_PORT" --format '{{.Names}}' | head -1)"
  [ -n "$c" ] || { echo "no running container publishes port $SEED_REDIS_PORT (set SEED_REDIS_CONTAINER)" >&2; return 1; }
  printf '%s' "$c"
}
seed_rcli() { docker exec "$(seed_redis_container)" redis-cli "$@"; }
# 표준 입력의 명령 묶음(redis-cli --pipe 형식)을 넘긴다
seed_rcli_pipe() { docker exec -i "$(seed_redis_container)" redis-cli "$@"; }

# seed_kafka <kafka CLI> [인자…] — 예: seed_kafka kafka-topics --list
seed_kafka() {
  local cli="$1"; shift
  docker exec "$SEED_KAFKA_CONTAINER" "$cli" --bootstrap-server localhost:9093 "$@"
}
