#!/usr/bin/env bash
# T2-7 시험: 스크립트 입구 점검 두 가지 — boot-smoke.sh 는 lsof 가 없으면, verify.sh 는 SEED_VERIFY_TOL_SCALE 이 숫자가 아니면 exit 2 로 멈춘다
# 데이터베이스 · Redis 를 바꾸지 않는다: 시험이 띄우는 스크립트의 PATH 에는 docker 를 넣지 않아, 입구 점검이 빠져도 스크립트가 어디에도 닿지 못하고 실패한다
# 환경 변수: SEED_PG_CONTAINER · SEED_REDIS_PORT 는 넘겨도 쓰이지 않는다(docker 가 PATH 에 없다)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
BASH_BIN="$(command -v bash)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAILS=0
fail() { echo "FAIL(test) $*"; FAILS=$((FAILS + 1)); }

# 스크립트가 입구 점검 전에 쓰는 명령만 심볼릭 링크로 둔 PATH — lsof · docker 는 일부러 뺀다
mkdir "$TMP/bin"
for c in env dirname mktemp rm awk grep find wc tr cat; do ln -s "$(command -v "$c")" "$TMP/bin/$c"; done

# run <명령…> — 종료 코드를 $code, 출력을 $out 에 담는다
run() {
  set +e
  out="$(PATH="$TMP/bin" "$@" 2>&1)"
  code=$?
  set -e
}

echo "== boot-smoke.sh without lsof exits 2 naming lsof before it resets anything"
run "$BASH_BIN" "$HERE/boot-smoke.sh"
[ "$code" -eq 2 ] || fail "[boot-smoke lsof] exit $code, want 2"
printf '%s\n' "$out" | grep -q 'lsof' || fail "[boot-smoke lsof] message does not name lsof: $out"
if printf '%s\n' "$out" | grep -q '^== reset'; then fail "[boot-smoke lsof] it reset the database before the check"; fi

echo "== verify.sh refuses a SEED_VERIFY_TOL_SCALE that is not a number"
for bad in abc 1x '' ' 1' '1.' '-1'; do
  # 빈 문자열은 '설정 안 함' 과 같아 거절 대상이 아니다
  [ -n "$bad" ] || continue
  run env SEED_VERIFY_TOL_SCALE="$bad" "$BASH_BIN" "$SEED/verify.sh" shop_seed_t2guard 0
  [ "$code" -eq 2 ] || fail "[tol number '$bad'] exit $code, want 2"
  printf '%s\n' "$out" | grep -q 'SEED_VERIFY_TOL_SCALE.*not a number' || fail "[tol number '$bad'] message does not say 'not a number': $out"
done
echo "== verify.sh lets a numeric SEED_VERIFY_TOL_SCALE through the guard"
for good in 1 1.5 10; do
  run env SEED_VERIFY_TOL_SCALE="$good" "$BASH_BIN" "$SEED/verify.sh" shop_seed_t2guard 1
  # 입구 점검을 지나면 docker 가 없어 exit 1(psql 실패)이다 — 2 가 아니어야 하고 '숫자가 아니다' 말도 없어야 한다
  [ "$code" -ne 2 ] || fail "[tol number '$good'] refused with exit 2: $out"
  if printf '%s\n' "$out" | grep -q 'not a number'; then fail "[tol number '$good'] called a number 'not a number'"; fi
done

if [ "$FAILS" -ne 0 ]; then echo "guards-test: $FAILS failure(s)"; exit 1; fi
echo "guards-test: OK"
