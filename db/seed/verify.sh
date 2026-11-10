#!/usr/bin/env bash
# 시드 DB 의 불변식을 검사한다. 모든 검사가 통과해야 exit 0, 하나라도 실패하면 exit 1 (한 줄에 검사 하나: PASS/FAIL 이름: 설명)
# 사용: bash db/seed/verify.sh <database> <scale>
#   <scale> 은 그 DB 를 만들 때의 SEED_SCALE (예: 1, 0.01) — 목표 행 수 = scale 1 의 목표 × scale
# 환경 변수(기본값): SEED_PG_CONTAINER (dev-db) · SEED_PG_USER (dev) — 읽기만 한다. 아무것도 지우지 않는다
# 검사 묶음: counts · flyway · refs · enums · amounts · flash-stock · coupon-quota · sequences · minority · skew · raffle
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

[ $# -eq 2 ] || { echo "usage: verify.sh <database> <scale>" >&2; exit 2; }
DB="$1"
SCALE="$2"

set +e
out="$(cat "$HERE/sql/00_sizes.sql" "$HERE/verify.sql" | seed_psql_stdin "$DB" -At -v scale="$SCALE" -f - 2>&1)"
code=$?
set -e
printf '%s\n' "$out"
[ "$code" -eq 0 ] || { echo "FAIL verify: psql exit $code" >&2; exit 1; }
if printf '%s\n' "$out" | grep -q '^FAIL '; then exit 1; fi
