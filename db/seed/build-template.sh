#!/usr/bin/env bash
# 시드 템플릿 DB 를 처음부터 만든다: DB 를 지우고 다시 만들어 Flyway 마이그레이션 파일을 그대로 적용한 뒤 시드를 넣고 템플릿으로 표시한다
# 사용: bash db/seed/build-template.sh
# 환경 변수(기본값):
#   SEED_TEMPLATE_DB     만들 DB 이름 (shop_seed_template) — shop_seed 로 시작해야 한다
#   SEED_SCALE           규모 배수 (1) — 1 은 사용자 10만 · 상품 5만 · 주문 30만 · 결제 30만 · 쿠폰 발급 20만 …
#   SEED_PG_CONTAINER    PostgreSQL 컨테이너 (dev-db) · SEED_PG_USER (dev) · SEED_PG_PASSWORD (dev123)
#   SEED_MIGRATION_DIR   Flyway 파일 위치 (<저장소>/backend/src/main/resources/db/migration)
#   SEED_FLYWAY_IMAGE    Flyway 이미지 (flyway/flyway:8.5.13 — 애플리케이션의 flyway-core 와 같은 버전)
# 지우는 것: SEED_TEMPLATE_DB 이름의 DB 하나(있으면 세션을 끊고 지운 뒤 다시 만든다). Redis · Kafka 는 건드리지 않는다
# 끝에 표별 행 수와 걸린 초 · DB 크기를 표준 출력에 찍는다
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

SCALE="${SEED_SCALE:-1}"
DB="$SEED_TEMPLATE_DB"
MIG="${SEED_MIGRATION_DIR:-$HERE/../../backend/src/main/resources/db/migration}"
FLYWAY_IMAGE="${SEED_FLYWAY_IMAGE:-flyway/flyway:8.5.13}"
seed_check_name "$DB"
MIG="$(cd "$MIG" && pwd)"
START="$(seed_now)"

echo "== recreate $DB"
seed_drop_db "$DB"
seed_psql postgres -c "CREATE DATABASE $DB" > /dev/null

echo "== flyway migrate ($MIG)"
# 컨테이너가 PostgreSQL 컨테이너의 네트워크를 빌려 localhost:5432 로 붙는다. 마이그레이션 파일은 읽기 전용으로 마운트한다
docker run --rm --network "container:$SEED_PG_CONTAINER" -v "$MIG":/flyway/sql:ro "$FLYWAY_IMAGE" \
  -url="jdbc:postgresql://localhost:5432/$DB" -user="$SEED_PG_USER" -password="$SEED_PG_PASSWORD" \
  -connectRetries=5 migrate | tail -2

echo "== seed at scale $SCALE"
# 한 psql 세션에 파일을 순서대로 이어 넣는다(임시 표 · 규모 변수가 파일 사이에 이어진다). 파일마다 표 하나씩 트랜잭션이다
cat "$HERE"/sql/*.sql | seed_psql_stdin "$DB" -v scale="$SCALE" -f - > /dev/null

echo "== mark as template"
seed_psql postgres -c "ALTER DATABASE $DB IS_TEMPLATE true" > /dev/null

echo "== row counts ($DB, scale $SCALE)"
seed_psql_stdin "$DB" -At -F ' = ' <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I', tablename, tablename) FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
\gexec
SQL
END="$(seed_now)"
echo "database size: $(seed_psql postgres -Atc "SELECT pg_size_pretty(pg_database_size('$DB')) || ' (' || pg_database_size('$DB') || ' bytes)'")"
echo "build seconds: $(perl -e "printf '%.1f', $END - $START")"
