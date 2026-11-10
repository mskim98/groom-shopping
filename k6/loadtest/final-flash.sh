#!/usr/bin/env bash
# r120 최종 측정 - 플래시 구매 단계를 순서대로 돈다(tiny → 2000 가상 → 2000 플랫폼 → 300 · 600 · 1000 · 1500 가상). 단계마다 백엔드를 멈추고 새 시드 복제 → 앱 기동 → 점검 → 단계 → 수집
# 사용: bash k6/loadtest/final-flash.sh <출력 폴더>   (스택의 infra 는 떠 있어야 한다)
# 폴더 <출력>/<라벨>/ 마다 run.sh 의 단계 폴더가 들어가고, 라벨별 result-<라벨>.json 을 collect 가 만든다
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:?usage: final-flash.sh <out-dir>}"
RUN="bash $ROOT/k6/loadtest/run.sh"
mkdir -p "$OUT"
cd "$ROOT"

reseed_and_boot() { # 스레드 종류 true|false
  docker stop nginx r119-measure-backend1-1 r119-measure-backend2-1 r119-measure-backend3-1 > /dev/null 2>&1 || true
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

# tiny - 스택은 이미 새 시드로 떠 있다(템플릿 재구성 직후) - 가상 스레드, 누수 감지 끔
echo "== $(date -u +%T) tiny"
$RUN tiny "$OUT/tiny" > "$OUT/tiny.run.log" 2>&1
$RUN collect "$OUT/tiny" "$OUT/result-tiny.json" >> "$OUT/tiny.run.log" 2>&1
tail -2 "$OUT/tiny.run.log"

step_run virtual-2000 2000 true
step_run platform-2000 2000 false
for r in 300 600 1000 1500; do step_run virtual-$r $r true; done
echo "== $(date -u +%T) all steps done"
