#!/usr/bin/env bash
# 측정 환경 점검 - backend1..3 건강 UP · nginx 80 번이 상품 목록 응답 · wiremock 이 결제 승인에 200 · 시드 DB 가 verify.sh 통과 + pg_stat_statements 확장 있음
# 하나라도 어긋나면 exit 1 (한 줄에 검사 하나: PASS/FAIL 이름)
# 사용: bash k6/loadtest/check-env.sh   (run.sh app 이 끝난 뒤)
set -uo pipefail
. "$(dirname "$0")/env.sh"

bad=0
check() { # 이름 · 명령(문자열)
  local name="$1"; shift
  if out="$("$@" 2>&1)"; then echo "PASS $name"; else echo "FAIL $name: $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"; bad=1; fi
}

for b in $BACKENDS; do
  # 백엔드 포트는 호스트에 게시하지 않는다 - 같은 네트워크의 nginx 컨테이너 안에서 부른다
  check "health $b" bash -c "docker exec nginx curl -fsS http://$b:8080/api/actuator/health | grep -q '\"status\":\"UP\"'"
done
check "nginx:80 product list" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' 'http://localhost/api/v1/product?size=1')\" = 200 ]"
check "wiremock confirm 200" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18089/v1/payments/confirm -H 'Content-Type: application/json' -d '{\"paymentKey\":\"k\",\"orderId\":\"o\",\"amount\":1}')\" = 200 ]"
check "verify.sh $SEED_DB 1" bash "$ROOT/db/seed/verify.sh" "$SEED_DB" 1
check "pg_stat_statements extension" bash -c ". '$ROOT/k6/loadtest/env.sh'; [ \"\$(psql_seed -Atc \"SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'\")\" = 1 ]"
exit $bad
