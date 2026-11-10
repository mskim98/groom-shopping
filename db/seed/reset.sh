#!/usr/bin/env bash
# 대상 DB 를 템플릿의 처음 상태로 되돌린다: 대상의 세션을 끊고, 지우고, CREATE DATABASE … TEMPLATE 로 다시 만든다
# 사용: bash db/seed/reset.sh <target-database>
# 환경 변수(기본값):
#   SEED_TEMPLATE_DB   복제 원본 템플릿 (shop_seed_template) — build-template.sh 로 만든다
#   SEED_PG_CONTAINER  PostgreSQL 컨테이너 (dev-db) · SEED_PG_USER (dev)
# 지우는 것: <target-database> 하나(세션을 끊고 DROP 한 뒤 템플릿에서 다시 만든다). 이름이 shop_seed 로 시작하지 않거나
#   템플릿과 같으면 아무것도 지우지 않고 exit 2. Redis · Kafka 는 건드리지 않는다(load-redis.sh · recreate-topics.sh)
# 끝에 걸린 초를 찍는다
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

[ $# -eq 1 ] || { echo "usage: reset.sh <target-database>" >&2; exit 2; }
TARGET="$1"
TEMPLATE="$SEED_TEMPLATE_DB"

seed_check_name "$TARGET" || exit 2
seed_check_name "$TEMPLATE" || exit 2
if [ "$TARGET" = "$TEMPLATE" ]; then
  echo "refused: target '$TARGET' is the template itself" >&2
  exit 2
fi
if [ "$(seed_psql postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$TEMPLATE' AND datistemplate")" != 1 ]; then
  echo "template database '$TEMPLATE' is missing or not marked as template — run build-template.sh first" >&2
  exit 1
fi

START="$(seed_now)"
seed_drop_db "$TARGET"
# 템플릿에 접속한 세션이 아직 닫히는 중이면 CREATE DATABASE 가 잠깐 실패한다 — 몇 번 다시 시도한다
for attempt in 1 2 3 4 5; do
  if seed_psql postgres -c "CREATE DATABASE $TARGET TEMPLATE $TEMPLATE" 2> /dev/null; then break; fi
  [ "$attempt" -lt 5 ] || { seed_psql postgres -c "CREATE DATABASE $TARGET TEMPLATE $TEMPLATE"; }
  sleep 1
done
END="$(seed_now)"
echo "reset $TARGET from $TEMPLATE: $(perl -e "printf '%.1f', $END - $START") seconds"
