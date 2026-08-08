#!/usr/bin/env bash
# 사용법: run_p2.sh <출력이름> <VUS> <ITERATIONS>
# 재고 리셋 -> k6 실행 -> 정합성/재시도 기록까지 한 번에 한다.
set -e
NAME=$1; VUS=$2; ITERS=${3:-10000}
cd /Users/mskim/Desktop/PJ/groom-shopping
ID=550e8400-e29b-41d4-a716-446655440000
OUT=backend/docs/measurements/p2/${NAME}.json

./k6/reset_hot_stock.sh > /dev/null
# 재시도 카운터는 앱 생애 누적이라 런 직전 값을 빼서 이 런의 증분만 본다
# 앱 재기동 직후에는 이 메트릭이 아직 없다(첫 차감 전) -> 없으면 0,0 으로 둔다
retries() { curl -s localhost:8080/api/actuator/prometheus \
  | awk '/^stock_optimistic_retries_sum\{op="decrease"\}/{s=$2} /^stock_optimistic_retries_count\{op="decrease"\}/{c=$2} END{printf "%s,%s", (s==""?0:s), (c==""?0:c)}'; }
BEFORE=$(retries)

k6 run -e HOT_PRODUCT_ID=$ID -e USERS=200 -e VUS=$VUS -e ITERATIONS=$ITERS \
  --summary-trend-stats="avg,med,p(90),p(95),p(99),max" \
  --summary-export=$OUT --no-thresholds --quiet \
  k6/scripts/stock-concurrency-test.js > /dev/null 2>&1

AFTER=$(retries)
DB=$(docker exec -e PGPASSWORD=$(grep POSTGRES_PASSWORD .env|cut -d= -f2|tr -d '\r') dev-db psql -U dev -d shopping_db_dev -tAc "SELECT stock FROM product WHERE id='$ID';" | tr -d ' ')
RD=$(docker exec dev-redis redis-cli GET product:stock:$ID)

python3 - "$OUT" "$NAME" "$DB" "$RD" "$BEFORE" "$AFTER" <<'PY'
import json,sys
out,name,db,rd,before,after=sys.argv[1:7]
m=json.load(open(out))['metrics']
g=lambda k: m.get(k,{}).get('count',0)
cl=m['confirm_latency']
s=g('confirm_success')
bs,bc=[float(x) for x in before.split(',')]; as_,ac=[float(x) for x in after.split(',')]
retry=(as_-bs)/(ac-bc) if ac>bc else 0
ok = int(db)==10000-s and int(rd)==int(db)
print(json.dumps(dict(run=name, vus=m['vus_max']['value'], iters=g('iterations'),
  success=s, conflict=g('confirm_conflict'), blocked=g('confirm_blocked'), other=g('confirm_other_fail'),
  avg=round(cl['avg'],1), p95=round(cl['p(95)'],1), p99=round(cl['p(99)'],1),
  retry_per_req=round(retry,3), db=int(db), redis=int(rd), consistent=ok), ensure_ascii=False))
PY
