# run.sh · check-env.sh 가 함께 쓰는 환경 - 직접 실행하지 않고 `. env.sh` 로 불러 쓴다
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT="r119-measure"
SEED_DB="shop_seed_m1"
DC="docker compose -p $PROJECT -f $ROOT/docker-compose.yml -f $ROOT/k6/loadtest/docker-compose.loadtest.yml"
NGINX_LOG="/var/log/nginx/loadtest.access.log"
BACKENDS="backend1 backend2 backend3"

# .env 의 값 하나(비밀번호를 셸 명령에 풀어 쓰지 않으려고 source 하지 않고 한 줄만 읽는다)
envval() { grep -E "^$1=" "$ROOT/.env" | head -1 | cut -d= -f2-; }

# 시드 스크립트(db/seed)가 compose 의 db · redis · kafka 컨테이너를 보게 한다
export SEED_PG_CONTAINER=db
export SEED_PG_USER="$(envval POSTGRES_USER)"
export SEED_PG_PASSWORD="$(envval POSTGRES_PASSWORD)"
export SEED_KAFKA_CONTAINER=kafka
export SEED_REDIS_PORT=6379
export SEED_REDIS_CONTAINER=redis

psql_seed() { docker exec db psql -U "$SEED_PG_USER" -d "$SEED_DB" -X -q -v ON_ERROR_STOP=1 "$@"; }
