#!/usr/bin/env bash
# r120 최종 측정 M-5 - 가짜 PG(wiremock)가 취소를 전부 실패시키는 구간에 보상 배치가 도는 동안의 취소 호출 · 차단을 잰다
# 사용: bash k6/loadtest/final-pg-cancel.sh <출력 폴더>   (스택이 새 시드로 떠 있어야 한다: run.sh seed · app 직후)
# 장애 주입: wiremock 에 우선순위 1 스텁 - POST /v1/payments/*/cancel → 500 (응답 지연 CANCEL_DELAY_MS, 기본 300 ms), 구간 길이 WINDOW_S(기본 180 s)
# 부하: 취소 대기(PENDING · 지금 시도할 수 있음) 보상 행 DUE_ROWS(기본 1500)건을 DB 에 한꺼번에 넣는다 - 배치는 한 회차에 100건(batch-size)이라 한 페이지로 비우지 못한다
#   (보상 대상 결제는 FAILED · ABORTED 결제 - DONE · CANCELED 결제는 배치가 PG 를 부르지 않고 SUPERSEDED 로 끝낸다)
# 낸 값: pg-cancel.json - cancel_calls(구간 안에 wiremock 이 받은 취소 요청 수) · cancel_p95_ms(그 요청의 wiremock 총 처리 시간 p95) · pg_not_reached_ratio(백엔드 3대의 배치 회차 중 toss-cancel OPEN 으로 쉰 회차 / 전체 회차)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/k6/loadtest/env.sh"
OUT="${1:?usage: final-pg-cancel.sh <out-dir>}"
WM="http://localhost:18089"
WINDOW_S="${WINDOW_S:-180}"
DUE_ROWS="${DUE_ROWS:-1500}"
CANCEL_DELAY_MS="${CANCEL_DELAY_MS:-300}"
mkdir -p "$OUT"
cd "$ROOT"

# 장애 스텁
STUB="$(curl -fsS -X POST "$WM/__admin/mappings" -H 'Content-Type: application/json' -d "{\"priority\":1,\"request\":{\"method\":\"POST\",\"urlPathPattern\":\"/v1/payments/.*/cancel\"},\"response\":{\"status\":500,\"fixedDelayMilliseconds\":$CANCEL_DELAY_MS,\"headers\":{\"Content-Type\":\"application/json\"},\"jsonBody\":{\"code\":\"PROVIDER_ERROR\",\"message\":\"injected cancel outage\"}}}")"
STUB_ID="$(printf '%s' "$STUB" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
curl -fsS -X DELETE "$WM/__admin/requests" > /dev/null
START="$(date -u +%FT%TZ)"
echo "$START" > "$OUT/start.txt"
echo "{\"stub_id\":\"$STUB_ID\",\"window_s\":$WINDOW_S,\"due_rows\":$DUE_ROWS,\"cancel_delay_ms\":$CANCEL_DELAY_MS}" > "$OUT/injection.json"

# 지금 시도할 수 있는 대기 행을 넣는다(결제당 하나, 보상이 아직 없는 FAILED · ABORTED 결제)
psql_seed -Atc "INSERT INTO payment_compensation (id, payment_id, payment_key, amount, reason, status, retry_count, max_retry_count, next_retry_at, created_at, updated_at)
  SELECT gen_random_uuid(), p.id, coalesce(p.payment_key, 'r120_pk_' || p.id::text), p.amount, 'r120 measure: injected cancel outage', 'PENDING', 0, 5, now() AT TIME ZONE 'Asia/Seoul' - interval '1 minute', now() AT TIME ZONE 'Asia/Seoul', now() AT TIME ZONE 'Asia/Seoul'
    FROM payment p WHERE p.status IN ('FAILED', 'ABORTED') AND NOT EXISTS (SELECT 1 FROM payment_compensation c WHERE c.payment_id = p.id)
   ORDER BY p.id LIMIT $DUE_ROWS" > "$OUT/insert.txt"
psql_seed -Atc "SELECT status, count(*) FROM payment_compensation GROUP BY 1 ORDER BY 1" > "$OUT/comp-before.txt"

# 구간 동안 5 초마다 백엔드의 보상 지표(대기 수 · 적재 수)와 toss-cancel 회로 상태를 모은다
END_AT=$(( $(date +%s) + WINDOW_S ))
: > "$OUT/prom.tsv"
while [ "$(date +%s)" -lt "$END_AT" ]; do
  for b in $BACKENDS; do
    ts="$(date -u +%FT%TZ)"
    docker exec nginx curl -fsS "http://$b:8080/api/actuator/prometheus" 2> /dev/null \
      | grep -E '^(payment_compensation_(due|carried|given_up_total)|resilience4j_circuitbreaker_state\{[^}]*name="toss-cancel")' | sed "s/^/$ts\t$b\t/" >> "$OUT/prom.tsv" || true
  done
  sleep 5
done
END="$(date -u +%FT%TZ)"
echo "$END" > "$OUT/end.txt"

# 장애 해제 · 기록
curl -fsS "$WM/__admin/requests?limit=100000" > "$OUT/wiremock-requests.json"
curl -fsS -X DELETE "$WM/__admin/mappings/$STUB_ID" > /dev/null
psql_seed -Atc "SELECT status, count(*) FROM payment_compensation GROUP BY 1 ORDER BY 1" > "$OUT/comp-after.txt"
for b in $BACKENDS; do
  docker logs --since "$START" --until "$END" "${PROJECT}-$b-1" 2>&1 | grep -E 'PAYMENT_COMPENSATION_BATCH|TOSS_API_LATENCY.*/cancel - ' > "$OUT/batch-$b.log" || true
done
# 낸 값은 원시 파일에서 derive-final.py 가 만든다(스택 없이 다시 돌 수 있다)
python3 -B "$ROOT/k6/loadtest/derive-final.py" pgcancel "$OUT" "$OUT/pg-cancel.json"
