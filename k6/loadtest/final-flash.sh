#!/usr/bin/env bash
# 최종 측정 - 플래시 구매 단계를 순서대로 돈다. 단계마다 백엔드를 멈추고 새 시드 복제 → 앱 기동(SERVERS 대) → 점검 → 단계 → 수집
# 사용: bash k6/loadtest/final-flash.sh <출력 폴더> [라벨…]   (스택의 infra 는 떠 있어야 한다)
#   라벨 = tiny | virtual-<rps> | platform-<rps>, 주지 않으면 r120 의 순서(tiny virtual-2000 platform-2000 virtual-300 virtual-600 virtual-1000 virtual-1500)
#   결과 JSON 은 이 스크립트가 만들지 않는다 - 원시 폴더에서 derive-final.py all 이 만든다
# 폴더 <출력>/<라벨>/ 마다 run.sh 의 단계 폴더가 들어가고, 라벨별 result-<라벨>.json 을 collect 가 만든다
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/k6/loadtest/env.sh" || exit 1
OUT="${1:?usage: final-flash.sh <out-dir> [label...]}"; shift
LABELS="${*:-tiny virtual-2000 platform-2000 virtual-300 virtual-600 virtual-1000 virtual-1500}"
RUN="bash $ROOT/k6/loadtest/run.sh"
mkdir -p "$OUT"
cd "$ROOT"

reseed_and_boot() { # 스레드 종류 true|false
  stop_backends
  $RUN seed > "$OUT/seed-last.log" 2>&1 || { echo "seed failed" >&2; return 1; }
  SPRING_THREADS_VIRTUAL_ENABLED="$1" $RUN app > "$OUT/app-last.log" 2>&1 || { echo "app failed" >&2; return 1; }
  bash k6/loadtest/check-env.sh > "$OUT/check-last.log" 2>&1 || { echo "check-env failed" >&2; return 1; }
}

step_run() { # 라벨 · rps · 스레드 true|false
  local label="$1" rps="$2" vt="$3"
  echo "== $(date -u +%T) $label"
  reseed_and_boot "$vt" || return 1
  SPRING_THREADS_VIRTUAL_ENABLED="$vt" $RUN step "$rps" "$OUT/$label" > "$OUT/$label.run.log" 2>&1
  $RUN collect "$OUT/$label" "$OUT/result-$label.json" >> "$OUT/$label.run.log" 2>&1
  cp "$OUT/seed-last.log" "$OUT/$label/seed.log"
  tail -2 "$OUT/$label.run.log"
}

tiny_run() {
  echo "== $(date -u +%T) tiny"
  reseed_and_boot true || return 1
  $RUN tiny "$OUT/tiny" > "$OUT/tiny.run.log" 2>&1
  $RUN collect "$OUT/tiny" "$OUT/result-tiny.json" >> "$OUT/tiny.run.log" 2>&1
  cp "$OUT/seed-last.log" "$OUT/tiny/seed.log"
  tail -2 "$OUT/tiny.run.log"
}

for label in $LABELS; do
  case "$label" in
    tiny) tiny_run ;;
    virtual-*) step_run "$label" "${label#*-}" true ;;
    platform-*) step_run "$label" "${label#*-}" false ;;
    *) echo "unknown label $label (tiny | virtual-<rps> | platform-<rps>)" >&2; exit 2 ;;
  esac
done
echo "== $(date -u +%T) all steps done: $LABELS"
