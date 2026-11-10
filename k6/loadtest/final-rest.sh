#!/usr/bin/env bash
# r120 최종 측정 M-4 · M-6 · M-5 - 쿠폰 발급 · 상품 상세 조회 · PG 취소 장애. 시나리오마다 백엔드를 멈추고 새 시드 복제 → (시나리오 준비) → 앱 기동(가상 스레드, 누수 감지 끔) → 점검 → 실행
# 사용: bash k6/loadtest/final-rest.sh <출력 폴더> [coupon detail pgcancel 중 돌릴 것들, 기본 셋 다 이 순서]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/k6/loadtest/env.sh"
OUT="${1:?usage: final-rest.sh <out-dir> [coupon] [detail] [pgcancel]}"; shift
WHAT="${*:-coupon detail pgcancel}"
RUN="bash $ROOT/k6/loadtest/run.sh"
mkdir -p "$OUT"
cd "$ROOT"

stop_backends() { docker stop nginx r119-measure-backend1-1 r119-measure-backend2-1 r119-measure-backend3-1 > /dev/null 2>&1 || true; }
boot() {
  SPRING_THREADS_VIRTUAL_ENABLED=true $RUN app > "$OUT/app-last.log" 2>&1 || { echo "app failed" >&2; return 1; }
  bash k6/loadtest/check-env.sh > "$OUT/check-last.log" 2>&1 || { echo "check-env failed" >&2; return 1; }
}
reseed() { $RUN seed > "$OUT/seed-last.log" 2>&1 || { echo "seed failed" >&2; return 1; }; }
# 백엔드 이름별 요청 수 - nginx 로그에서 URI 정규식에 맞는 줄을 $upstream_addr 로 센다(backends.tsv 는 컨테이너 IP → 이름)
backend_split() { # 폴더 · URI 정규식 · nginx 로그 줄 시작
  for b in $BACKENDS; do echo "$b $(docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJECT}_app-network\").IPAddress}}" "${PROJECT}-$b-1")"; done > "$1/backends.tsv"
  docker exec nginx tail -n +"$3" "$NGINX_LOG" > "$1/nginx.log"
  python3 -B - "$1" "$2" <<'PY'
import json, re, sys, os
d, rx = sys.argv[1:3]
ip = {l.split()[1]: l.split()[0] for l in open(os.path.join(d, "backends.tsv")) if l.strip()}
cnt = {}; st = {}
for l in open(os.path.join(d, "nginx.log")):
    f = l.split(None, 5)
    if len(f) < 6 or not re.search(rx, f[2]): continue
    st[f[3]] = st.get(f[3], 0) + 1
    a = f[5].rsplit(None, 1)[0] if " " in f[5] else f[5]
    n = ip.get(a.split(",")[-1].strip().split(":")[0], "unknown")
    cnt[n] = cnt.get(n, 0) + 1
json.dump({"backend_requests": cnt, "nginx_status": st}, open(os.path.join(d, "split.json"), "w"))
PY
}
nginx_lines() { docker exec nginx sh -c "wc -l < $NGINX_LOG" | tr -d ' '; }

coupon() {
  local d="$OUT/coupon"; mkdir -p "$d"
  echo "== $(date -u +%T) coupon"
  stop_backends; reseed || return 1
  local qty="${COUPON_QTY:-1000}" rate="${COUPON_RATE:-500}" dur="${COUPON_DURATION_S:-6}"
  # 새 쿠폰 하나(수량 qty, 발급 행 없음) - 백엔드가 멈춘 동안 DB 에 넣고 Redis 재고 키를 다시 적재한다
  local cid
  cid="$(psql_seed -Atc "INSERT INTO coupon (id, name, description, quantity, amount, minimum_cost, is_active, type, expire_date, created_at, updated_at)
    SELECT max(id) + 1, 'r120 measure coupon', 'r120 measure coupon', $qty, 5000, 30000, true, 'DISCOUNT', (now() AT TIME ZONE 'Asia/Seoul')::date + 30, now() AT TIME ZONE 'Asia/Seoul', now() AT TIME ZONE 'Asia/Seoul' FROM coupon RETURNING id" | head -1)"
  bash db/seed/load-redis.sh "$SEED_DB" > "$d/load-redis.log" 2>&1
  boot || return 1
  psql_seed -c "SELECT pg_stat_statements_reset()" > /dev/null
  docker exec redis redis-cli GET "coupon:{$cid}:stock" > "$d/stock-before.txt"
  local before; before="$(nginx_lines)"
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null
  docker exec redis redis-cli SLOWLOG RESET > /dev/null
  local t0; t0="$(date -u +%FT%TZ)"
  k6 run -q --no-usage-report -e COUPON_ID="$cid" -e RATE="$rate" -e DURATION_S="$dur" -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$d/k6-summary.json" \
    k6/scripts/coupon-issue-final.js > "$d/k6.log" 2>&1 || echo "k6 exit $?" >> "$d/k6.log"
  sleep 5
  # 증거 - Redis 명령 통계 · 느린 명령, 백엔드 로그의 경고 · 오류와 쿠폰 소비자 줄
  docker exec redis redis-cli INFO commandstats > "$d/redis-commandstats.txt"
  docker exec redis redis-cli SLOWLOG GET 20 > "$d/redis-slowlog.txt"
  for b in $BACKENDS; do
    docker logs --since "$t0" "${PROJECT}-$b-1" 2>&1 | grep -E ' (WARN|ERROR) ' | head -100 > "$d/warn-$b.log" || true
    docker logs --since "$t0" "${PROJECT}-$b-1" 2>&1 | grep -c -iE 'coupon' > "$d/coupon-lines-$b.txt" || true
  done
  psql_seed -At -F $'\t' -c "SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 120), calls, round(mean_exec_time::numeric, 2) FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') ORDER BY total_exec_time DESC LIMIT 8" > "$d/pgss.tsv" || true
  backend_split "$d" '/coupon/issue-async/[0-9]+$' "$((before + 1))"
  psql_seed -Atc "SELECT count(*) FROM coupon_issue WHERE coupon_id = $cid" > "$d/issued-db.txt"
  docker exec redis redis-cli GET "coupon:{$cid}:stock" > "$d/stock-after.txt"
  docker exec redis redis-cli SCARD "coupon:{$cid}:issued_users" > "$d/issued-users-redis.txt"
  python3 -B - "$d" "$cid" "$qty" "$t0" <<'PY'
import json, sys, os
d, cid, qty, t0 = sys.argv[1:5]
k = json.load(open(os.path.join(d, "k6-summary.json"))); s = json.load(open(os.path.join(d, "split.json")))
rd = lambda n: open(os.path.join(d, n)).read().strip()
out = {"coupon_id": int(cid), "quantity": int(qty), "requests": k["requests"], "rate": k["rate"], "duration_s": k["duration_s"],
       "issued": int(rd("issued-db.txt")), "completion_p95_ms": k["completion_p95_ms"], "completion_p99_ms": k["completion_p99_ms"],
       "completion_count": k["completion_count"], "accept_p95_ms": k["accept_p95_ms"], "results": k["results"],
       "dropped_iterations": k["dropped_iterations"], "max_vus": k["max_vus"], "backend_requests": s["backend_requests"], "nginx_status": s["nginx_status"],
       "redis_stock_before": rd("stock-before.txt"), "redis_stock_after": rd("stock-after.txt"), "redis_issued_users": rd("issued-users-redis.txt"),
       "backends": 3, "path": "POST /coupon/issue-async/{id} (Redis Lua + Stream consumer) then GET /coupon/issue-async/{requestId}/status until SUCCESS",
       "completion_definition": "request start to the first status poll that returns SUCCESS (polled every 100 ms)", "start": t0}
json.dump(out, open(os.path.join(d, "..", "coupon.json"), "w"), indent=2)
print({x: out[x] for x in ("requests", "quantity", "issued", "rate", "completion_p95_ms")})
PY
}

detail() {
  local d="$OUT/detail"; mkdir -p "$d"
  echo "== $(date -u +%T) detail"
  stop_backends; reseed || return 1
  boot || return 1
  local n="${DETAIL_PRODUCTS:-200}" rate="${DETAIL_RATE:-2000}" hold="${DETAIL_HOLD_S:-30}"
  psql_seed -Atc "SELECT id FROM product WHERE flash_sale = false ORDER BY id LIMIT $n" > "$d/product-ids.txt"
  # 기준선 - 10 초 동안 부하 없이 Redis GET 이 늘어나는 양(백엔드의 다른 일)
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null
  sleep 10
  docker exec redis redis-cli INFO commandstats > "$d/redis-baseline.txt"
  docker exec redis redis-cli CONFIG RESETSTAT > /dev/null
  psql_seed -c "SELECT pg_stat_statements_reset()" > /dev/null
  local before; before="$(nginx_lines)"
  local t0; t0="$(date -u +%FT%TZ)"
  k6 run -q --no-usage-report -e PRODUCT_IDS_FILE="$d/product-ids.txt" -e RATE="$rate" -e HOLD_S="$hold" -e BASE_URL=http://localhost/api/v1 -e SUMMARY_FILE="$d/k6-summary.json" \
    k6/scripts/product-detail-final.js > "$d/k6.log" 2>&1 || echo "k6 exit $?" >> "$d/k6.log"
  docker exec redis redis-cli INFO commandstats > "$d/redis-after.txt"
  psql_seed -At -F $'\t' -c "SELECT regexp_replace(left(query, 300), '\s+', ' ', 'g'), calls FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$SEED_DB') ORDER BY calls DESC LIMIT 10" > "$d/pgss.tsv"
  backend_split "$d" '^/api/v1/product/[0-9a-f-]{36}$' "$((before + 1))"
  echo "$t0" > "$d/start.txt"
  python3 -B - "$d" "$t0" <<'PY'
import json, os, re, sys
d, t0 = sys.argv[1:3]
rd = lambda n: open(os.path.join(d, n)).read()
k6 = json.load(open(os.path.join(d, "k6-summary.json"))); sp = json.load(open(os.path.join(d, "split.json")))
cmd = lambda txt, name: int((re.search(r"cmdstat_%s:calls=(\d+)" % name, txt) or [0, 0])[1])
after, base = rd("redis-after.txt"), rd("redis-baseline.txt")
# 기준선은 10 초 동안의 EXISTS · GET 이다 - 부하 구간(ramp + hold)의 길이만큼 늘려 뺀다
secs = k6.get("ramp_s", 5) + k6["hold_s"]
exists_base = cmd(base, "exists") * secs / 10
n = sum(sp["backend_requests"].values())
l2_lookups = max(cmd(after, "exists") - exists_base, 0)
rows = [l.split("\t") for l in rd("pgss.tsv").splitlines() if l.strip()]
calls = lambda rx: sum(int(r[1]) for r in rows if re.search(rx, r[0]))
body_loads = calls(r"^select pje1_0\.id")
qty_reads = calls(r"^SELECT stock, stock_mode FROM product WHERE id")
l1 = 1 - l2_lookups / n if n else None
l2 = 1 - body_loads / l2_lookups if l2_lookups else None
out = {
    "requests": k6["requests"], "requests_nginx_all_phases": n, "rate": k6["rate"], "hold_s": k6["hold_s"], "ramp_s": 5, "products": k6["products"],
    "vus_max": k6["max_vus"], "achieved_rps_hold": k6["achieved_rps"], "p95_ms": k6["p95_ms"], "p99_ms": k6["p99_ms"], "avg_ms": k6["avg_ms"],
    "ok": k6["ok"], "bad": k6["bad"], "dropped_iterations": k6["dropped_iterations"], "backend_requests": sp["backend_requests"],
    "cache_hit_ratio": 1 - body_loads / n if n else None,
    "cache_hit_ratio_definition": "overall = 1 - DB body loads / requests (a request is a hit when neither L1 nor L2 needed the database for the product body)",
    "l1_hit_ratio": l1, "l1_definition": "1 - L2 lookups / requests; L1 = server-memory Caffeine, 1 s TTL; L2 lookups = Redis EXISTS delta (one per L1 body miss, baseline-corrected)",
    "l2_hit_ratio": l2, "l2_definition": "1 - DB body loads / L2 lookups; L2 = Redis pointer+body, 30 s TTL; DB body loads = pg_stat_statements calls of the Product select by id (pje1_0)",
    "l2_lookups": l2_lookups, "db_body_loads": body_loads, "db_quantity_reads": qty_reads,
    "quantity_note": "the stock quantity has only an L1 (1 s) and no L2: every L1 expiry reads SELECT stock, stock_mode FROM product (db_quantity_reads) regardless of the body cache",
    "redis_exists_baseline_per_10s": cmd(base, "exists"), "redis_exists_after": cmd(after, "exists"), "redis_get_after": cmd(after, "get"),
    "other_traffic": "none issued by the driver; the stack's background jobs (outbox poller, stock ledger apply, reconcilers) run - see pgss_top for their statements",
    "pgss_top": [{"query": r[0][:120], "calls": int(r[1])} for r in rows[:6]], "start": t0,
}
json.dump(out, open(os.path.join(d, "..", "detail.json"), "w"), indent=2, ensure_ascii=False)
print({x: out[x] for x in ("requests", "rate", "p95_ms", "cache_hit_ratio", "l1_hit_ratio", "l2_hit_ratio", "db_body_loads", "db_quantity_reads")})
PY
}

pgcancel() {
  echo "== $(date -u +%T) pgcancel"
  stop_backends; reseed || return 1
  boot || return 1
  bash k6/loadtest/final-pg-cancel.sh "$OUT/pgcancel" > "$OUT/pgcancel.run.log" 2>&1
  cp "$OUT/pgcancel/pg-cancel.json" "$OUT/pg-cancel.json" 2> /dev/null || true
  tail -2 "$OUT/pgcancel.run.log"
}

for w in $WHAT; do "$w"; done
echo "== $(date -u +%T) all done: $WHAT"
