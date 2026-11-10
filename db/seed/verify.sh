#!/usr/bin/env bash
# 시드 DB 의 불변식을 검사한다. 모든 검사가 통과해야 exit 0, 하나라도 실패하면 exit 1 (한 줄에 검사 하나: PASS/FAIL 이름: 설명)
# 사용: bash db/seed/verify.sh <database> <scale>
#   <scale> 은 그 DB 를 만들 때의 SEED_SCALE (예: 1, 0.01) — 목표 행 수 = scale 1 의 목표 × scale
# 환경 변수(기본값): SEED_PG_CONTAINER (dev-db) · SEED_PG_USER (dev) — 읽기만 한다. 아무것도 지우지 않는다
#   SEED_VERIFY_TOL_SCALE (비어 있음 = <scale>) — 행 수 허용 오차 규칙을 고르는 배수(<scale> 이상이어야 한다 — 더 엄격하게만 만들 수 있고 작으면 exit 2). scale >= 1 이면 목표 300 이상은 ceil(1 %), 300 미만은 정확히 일치, scale < 1 이면 max(1 %, 3행)
# 검사 묶음: counts · flyway · refs · enums · amounts · flash-stock · coupon-quota · sequences · minority · skew · raffle
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

[ $# -eq 2 ] || { echo "usage: verify.sh <database> <scale>" >&2; exit 2; }
DB="$1"
SCALE="$2"
# SEED_VERIFY_TOL_SCALE 은 검사를 더 엄격하게만 만든다 — scale 보다 작으면 거절한다(scale 1 검증을 환경 변수로 느슨하게 만들 수 없다)
if [ -n "${SEED_VERIFY_TOL_SCALE:-}" ] && awk -v t="$SEED_VERIFY_TOL_SCALE" -v s="$SCALE" 'BEGIN { exit !(t + 0 < s + 0) }'; then
  echo "refused: SEED_VERIFY_TOL_SCALE=$SEED_VERIFY_TOL_SCALE is below scale $SCALE (it may only make the check stricter)" >&2
  exit 2
fi

set +e
# 마이그레이션이 늘어도 고칠 곳이 없게 파일 수를 세어 넘긴다 (V24 가 붙으면서 고정값 22 가 틀렸다)
MIG="${SEED_MIGRATION_DIR:-$HERE/../../backend/src/main/resources/db/migration}"
MIGRATIONS="$(find "$MIG" -name 'V*.sql' -print | wc -l | tr -d ' ')"
out="$(cat "$HERE/sql/00_sizes.sql" "$HERE/verify.sql" | seed_psql_stdin "$DB" -At -v scale="$SCALE" -v migrations="$MIGRATIONS" ${SEED_VERIFY_TOL_SCALE:+-v tol_scale="$SEED_VERIFY_TOL_SCALE"} -f - 2>&1)"
code=$?
set -e
# 11. raffle: 이름이 raffle 로 시작하는 표는 마이그레이션이 만든 행 그대로(raffles 10, 나머지 0). 읽기만 한다
# 검사 SQL 은 .sql 파일이 아니라 여기에 둔다 — db/seed 의 SQL 은 raffle 표를 이름으로도 언급하지 않는 규칙이다
if [ "$code" -eq 0 ]; then
  got="$(seed_psql_stdin "$DB" -At -F '=' <<'SQL' | sort | tr '\n' ' '
SELECT format('SELECT %L, count(*) FROM %I', tablename, tablename) FROM pg_tables WHERE schemaname = 'public' AND tablename LIKE 'raffle%'
\gexec
SQL
)"
  want="raffle_ticket_counters=0 raffle_tickets=0 raffle_winners=0 raffles=10 "
  if [ "$got" = "$want" ]; then out="$out"$'\n'"PASS raffle: $got"; else out="$out"$'\n'"FAIL raffle: got [$got] want [$want]"; fi
fi
printf '%s\n' "$out"
[ "$code" -eq 0 ] || { echo "FAIL verify: psql exit $code" >&2; exit 1; }
if printf '%s\n' "$out" | grep -q '^FAIL '; then exit 1; fi
