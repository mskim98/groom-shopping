#!/usr/bin/env python3
"""r120 최종 측정의 결과 JSON 을 원시 파일에서 다시 만든다 - 스택 없이 돈다(docker · psql 을 부르지 않는다)

usage:
  derive-final.py flash  <flash-dir> <out result.json>             tiny 와 단계별 result-<라벨>.json 을 result.json 한 개로 합친다(run.sh collect 가 만든 result-*.json 이 입력)
  derive-final.py coupon <coupon-dir> <out coupon.json> [--other 라벨=<coupon-dir> ...]   <coupon-dir> = final-rest.sh coupon 의 <out>/coupon 폴더
  derive-final.py detail <detail-dir> <out detail.json> [--background-pgss <pgss.tsv>]
  derive-final.py pgcancel <pgcancel-dir> <out pg-cancel.json>
조건 필드(threads · leak_detection · seed_copy)는 폴더의 condition.json(final-rest.sh 가 실행 때 쓴다)에서 읽는다. 없으면 이 도구를 쓴 스크립트의 고정 값을 쓰고 이유를 적는다:
final-rest.sh 의 boot 는 SPRING_THREADS_VIRTUAL_ENABLED=true 로 백엔드를 띄우고 compose 의 누수 감지 기본값은 0(끔)이다. seed_copy 는 그 실행이 남기지 않았으면 null 이다
"""
import glob
import json
import os
import re
import sys

FALLBACK_REASON = "no condition.json in the raw folder (the run predates the field): threads and leak_detection are the values final-rest.sh boots with (virtual threads, LEAK_DETECTION_MS 0); seed_copy was not recorded"


def rd(d, name, default=""):
    try:
        with open(os.path.join(d, name)) as f:
            return f.read().strip()
    except OSError:
        return default


def condition(d):
    try:
        c = json.loads(rd(d, "condition.json", "") or "null")
    except ValueError:
        c = None
    if c:
        return {"threads": c.get("threads"), "leak_detection": c.get("leak_detection"), "seed_copy": c.get("seed_copy"), "diag_samplers": c.get("diag_samplers")}
    return {"threads": "virtual", "leak_detection": False, "seed_copy": None, "diag_samplers": False, "condition_note": FALLBACK_REASON}


def pct(v, p):
    v = sorted(v)
    return v[max(0, min(len(v) - 1, -(-p * len(v) // 100) - 1))] if v else 0


def flash(src, out):
    res = {"tiny": json.load(open(os.path.join(src, "result-tiny.json")))["tiny"], "steps": []}
    for label in ("virtual-2000", "platform-2000", "virtual-300", "virtual-600", "virtual-1000", "virtual-1500"):
        step = json.load(open(os.path.join(src, "result-%s.json" % label)))["steps"][0]
        step["run_label"] = label
        res["steps"].append(step)
    json.dump(res, open(out, "w"), ensure_ascii=False, indent=1)
    print("wrote %s (tiny + %d steps)" % (out, len(res["steps"])))


def coupon_one(d):
    k = json.load(open(os.path.join(d, "k6-summary.json")))
    s = json.load(open(os.path.join(d, "split.json")))
    # 쿠폰 번호는 nginx 로그의 발급 요청 주소에서, 수량은 시작 때의 Redis 재고 키 값(= 쿠폰 수량)에서 읽는다
    m = re.search(r"/coupon/issue-async/(\d+) ", rd(d, "nginx.log"))
    out = {"coupon_id": int(m.group(1)) if m else None, "quantity": int(rd(d, "stock-before.txt")), "requests": k["requests"], "rate": k["rate"], "duration_s": k["duration_s"],
           "issued": int(rd(d, "issued-db.txt")), "completion_p95_ms": k["completion_p95_ms"], "completion_p99_ms": k["completion_p99_ms"],
           "completion_count": k["completion_count"], "accept_p95_ms": k["accept_p95_ms"], "results": k["results"],
           "dropped_iterations": k["dropped_iterations"], "max_vus": k["max_vus"], "backend_requests": s["backend_requests"], "nginx_status": s["nginx_status"],
           "redis_stock_before": rd(d, "stock-before.txt"), "redis_stock_after": rd(d, "stock-after.txt"), "redis_issued_users": rd(d, "issued-users-redis.txt"),
           "backends": 3, "path": "POST /coupon/issue-async/{id} (Redis Lua + Stream consumer) then GET /coupon/issue-async/{requestId}/status until SUCCESS",
           "completion_definition": "request start to the first status poll that returns SUCCESS (polled every 100 ms)", "start": rd(d, "start.txt") or None}
    out.update(condition(d))
    return out


def coupon(d, out, others):
    res = coupon_one(d)
    keep = ("rate", "duration_s", "requests", "issued", "completion_p95_ms", "completion_p99_ms", "accept_p95_ms", "results", "dropped_iterations", "max_vus")
    if others:
        res["other_rates"] = {label: {k: coupon_one(p)[k] for k in keep} for label, p in others}
        res["note"] = "coupon.json is the main run; the other runs of the same coupon path are listed under other_rates (500 rps saturated: accept p95 12-13 s)"
    json.dump(res, open(out, "w"), indent=2)
    print({x: res[x] for x in ("requests", "quantity", "issued", "rate", "completion_p95_ms")})


def detail(d, out, background):
    k6 = json.load(open(os.path.join(d, "k6-summary.json")))
    sp = json.load(open(os.path.join(d, "split.json")))
    cmd = lambda txt, name: int((re.search(r"cmdstat_%s:calls=(\d+)" % name, txt) or [0, 0])[1])
    after, base = rd(d, "redis-after.txt"), rd(d, "redis-baseline.txt")
    # 기준선은 10 초 동안 부하 없이 늘어난 EXISTS 다 - 부하 구간(ramp + hold)의 길이만큼 늘려 뺀다
    secs = k6.get("ramp_s", 5) + k6["hold_s"]
    exists_base = cmd(base, "exists") * secs / 10
    n = sum(sp["backend_requests"].values())
    l2_lookups = max(cmd(after, "exists") - exists_base, 0)
    parse = lambda txt: [l.split("\t") for l in txt.splitlines() if l.strip()]
    rows = parse(rd(d, "pgss.tsv"))
    calls = lambda rs, rx: sum(int(r[1]) for r in rs if re.search(rx, r[0]))
    raw_loads = calls(rows, r"^select pje1_0\.id")
    # 상품 select 가 하나 더 있다 - 상품 조회 요청이 없는 실행(쿠폰 실행)에서도 같은 횟수(153)로 찍히는 배경 작업이라 본문 적재 수에서 뺀다
    bg = calls(parse(open(background).read()), r"^select pje1_0\.id") if background else 0
    body_loads = raw_loads - bg
    qty_reads = calls(rows, r"^SELECT stock, stock_mode FROM product WHERE id")
    out_obj = {
        "requests": k6["requests"], "requests_nginx_all_phases": n, "rate": k6["rate"], "hold_s": k6["hold_s"], "ramp_s": 5, "products": k6["products"],
        "vus_max": k6["max_vus"], "achieved_rps_hold": k6["achieved_rps"], "p95_ms": k6["p95_ms"], "p99_ms": k6["p99_ms"], "avg_ms": k6["avg_ms"],
        "ok": k6["ok"], "bad": k6["bad"], "dropped_iterations": k6["dropped_iterations"], "backend_requests": sp["backend_requests"],
        "cache_hit_ratio": 1 - body_loads / n if n else None,
        "cache_hit_ratio_definition": "overall, product body cache only = 1 - DB body loads / requests (a request is a hit when neither L1 nor L2 needed the database for the product body); the stock quantity is not part of it (see db_quantity_reads)",
        "l1_hit_ratio": 1 - l2_lookups / n if n else None,
        "l1_definition": "1 - L2 lookups / requests; L1 = server-memory Caffeine, 1 s TTL; L2 lookups = Redis EXISTS delta (one per L1 body miss, baseline-corrected; one lookup then issues two GETs, pointer and body)",
        "l2_hit_ratio": 1 - body_loads / l2_lookups if l2_lookups else None,
        "l2_definition": "1 - DB body loads / L2 lookups; L2 = Redis pointer+body, 30 s TTL; DB body loads = pg_stat_statements calls of the Product select by id (pje1_0)",
        "l2_lookups": l2_lookups, "db_body_loads": body_loads, "db_body_loads_raw_two_statements": raw_loads, "db_body_loads_background_excluded": bg,
        "db_body_loads_background_source": background,
        "quantity_note": "the stock quantity has only an L1 (1 s) and no L2: every L1 expiry reads SELECT stock, stock_mode FROM product (db_quantity_reads) regardless of the body cache",
        "db_quantity_reads": qty_reads, "db_quantity_reads_per_request": qty_reads / n if n else None,
        "redis_exists_baseline_per_10s": cmd(base, "exists"), "redis_exists_after": cmd(after, "exists"), "redis_get_after": cmd(after, "get"),
        "other_traffic": "none issued by the driver; the stack's background jobs run (see pgss_top)",
        "pgss_top": [{"query": r[0][:120], "calls": int(r[1])} for r in rows[:6]], "start": rd(d, "start.txt") or None,
    }
    out_obj.update(condition(d))
    json.dump(out_obj, open(out, "w"), indent=2, ensure_ascii=False)
    print({x: out_obj[x] for x in ("requests", "rate", "p95_ms", "cache_hit_ratio", "l1_hit_ratio", "l2_hit_ratio", "db_body_loads", "db_quantity_reads")})


def pgcancel(d, out):
    inj = json.loads(rd(d, "injection.json"))
    start, end = rd(d, "start.txt"), rd(d, "end.txt")
    reqs = [e for e in json.load(open(os.path.join(d, "wiremock-requests.json")))["requests"] if re.search(r"/v1/payments/.*/cancel$", e["request"]["url"])]
    times = sorted(e["timing"]["totalTime"] for e in reqs if e.get("timing") and e["timing"].get("totalTime") is not None)
    skip = run = carried = 0
    per, lat = {}, []
    for p in sorted(glob.glob(os.path.join(d, "batch-*.log"))):
        t = open(p).read()
        lat += [int(x) for x in re.findall(r"/cancel - (\d+)ms", t)]
        s, r, c = t.count("PAYMENT_COMPENSATION_BATCH_SKIP"), len(re.findall(r"PAYMENT_COMPENSATION_BATCH\] Retrying", t)), t.count("PAYMENT_COMPENSATION_BATCH_CARRIED")
        per[os.path.basename(p)[6:-4]] = {"cycles_skipped": s, "cycles_run": r, "forced_open": c}
        skip += s
        run += r
        carried += c
    statuses = {}
    for e in reqs:
        key = str(e["response"]["status"])
        statuses[key] = statuses.get(key, 0) + 1
    res = {
        "fault_window_s": inj["window_s"], "window": [start, end], "cancel_stub": "500 after %s ms on every cancel" % inj["cancel_delay_ms"],
        "due_rows_injected": inj["due_rows"],
        "cancel_calls": len(reqs), "cancel_p95_ms": pct(lat, 95), "cancel_p99_ms": pct(lat, 99), "cancel_calls_backend_logs": len(lat),
        "cancel_wiremock_p95_ms": pct(times, 95), "cancel_response_status": statuses,
        "cancel_latency_source": "backend log [TOSS_API_LATENCY] POST .../cancel - <n>ms (client side, per call); cancel_calls counts the requests wiremock received",
        "batch_cycles_run": run, "batch_cycles_skipped_open": skip, "batch_forced_open": carried,
        "pg_not_reached_ratio": (skip / (skip + run)) if skip + run else 0,
        "pg_not_reached_definition": "batch cycles (3 backends) that did not call the PG because the toss-cancel stop flag was OPEN / all batch cycles in the window",
        "per_backend": per,
    }
    res.update(condition(d))
    json.dump(res, open(out, "w"), indent=2, ensure_ascii=False)
    print(json.dumps({k: res[k] for k in ("cancel_calls", "cancel_p95_ms", "pg_not_reached_ratio", "batch_cycles_run", "batch_cycles_skipped_open")}))


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    sub, src, out = argv[1:4]
    rest = argv[4:]
    if sub == "flash":
        flash(src, out)
    elif sub == "coupon":
        coupon(src, out, [tuple(x.split("=", 1)) for x in rest if x != "--other" and "=" in x])
    elif sub == "detail":
        bg = rest[rest.index("--background-pgss") + 1] if "--background-pgss" in rest else None
        detail(src, out, bg)
    elif sub == "pgcancel":
        pgcancel(src, out)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
