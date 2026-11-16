#!/usr/bin/env python3
"""r120 최종 측정의 결과 JSON 을 원시 파일에서 다시 만든다 - 스택 없이 돈다(docker · psql 을 부르지 않는다)

usage:
  derive-final.py all    <raw root> <out dir>                      네 JSON 을 한 번에 만든다. raw root 의 배치(final-flash.sh · final-rest.sh 의 <출력 폴더> 이름이 정한다):
                                                                     flash/{tiny/tiny, <라벨>/step-<rps>} · coupon-<rate>rps/coupon (가장 낮은 요청률이 주 실행, 나머지는 other_rates) ·
                                                                     detail/detail · pgcancel/pgcancel → <out dir>/{result,coupon,pg-cancel,detail}.json
                                                                     상세의 배경 상품 select 는 주 쿠폰 실행의 pgss.tsv(상품 조회 요청이 없는 실행)에서 센 횟수를 본문 적재 수에서 뺀다
  derive-final.py flash  <flash-dir> <out result.json>             tiny 와 단계 폴더(<flash-dir>/tiny/tiny · <flash-dir>/<라벨>/step-<rps>)를 collect.py 로 모아 result.json 한 개로 합친다
  derive-final.py coupon <coupon-dir> <out coupon.json> [--other 라벨=<coupon-dir> ...]   <coupon-dir> = final-rest.sh coupon 의 <out>/coupon 폴더. 같은 폴더에 summary.json 도 쓴다(환경 변수 CODE = base · new)
  derive-final.py detail <detail-dir> <out detail.json> [--background-pgss <pgss.tsv>]
  derive-final.py pgcancel <pgcancel-dir> <out pg-cancel.json>
조건 필드(threads · leak_detection · diag_samplers · seed_copy · servers)는 폴더의 condition.json(env.sh 의 write_condition 이 실행 때 쓴다)에서 읽는다.
condition.json 에 없는 값은 추측하지 않고 null 로 쓰고, 그 필드 이름을 condition_inferred 목록에 적는다(전부 있으면 빈 목록). servers 는 JSON 최상위에도 쓴다
"""
import glob
import json
import os
import re
import sys

from collect import collect_step, normalize_condition, read, stage_delta

REST_CONDITION_FIELDS = ("threads", "leak_detection", "diag_samplers", "seed_copy", "servers")
FLASH_ORDER = ("virtual-2000", "platform-2000", "virtual-300", "virtual-600", "virtual-1000", "virtual-1500")


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
    return normalize_condition(c or {}, REST_CONDITION_FIELDS)


def pct(v, p):
    v = sorted(v)
    return v[max(0, min(len(v) - 1, -(-p * len(v) // 100) - 1))] if v else 0


def flash(src, out):
    # tiny 를 돌리지 않은 재측정(1 대 단계 등)도 단계 결과는 만든다
    tiny_dir = os.path.join(src, "tiny", "tiny")
    res = {"tiny": collect_step(tiny_dir, 50) if os.path.isdir(tiny_dir) else None, "steps": []}
    found = sorted(os.path.basename(p) for p in glob.glob(os.path.join(src, "*")) if glob.glob(os.path.join(p, "step-*")))
    for label in [l for l in FLASH_ORDER if l in found] + [l for l in found if l not in FLASH_ORDER]:
        step_dir = glob.glob(os.path.join(src, label, "step-*"))[0]
        step = collect_step(step_dir, int(step_dir.rsplit("-", 1)[1]))
        step["run_label"] = label
        res["steps"].append(step)
    json.dump(res, open(out, "w"), ensure_ascii=False, indent=1)
    print("wrote %s (tiny=%s, %d steps)" % (out, res["tiny"] is not None, len(res["steps"])))


def coupon_one(d):
    k = json.load(open(os.path.join(d, "k6-summary.json")))
    s = json.load(open(os.path.join(d, "split.json")))
    # 쿠폰 번호는 nginx 로그의 발급 요청 주소에서, 수량은 시작 때의 Redis 재고 키 값(= 쿠폰 수량)에서 읽는다
    m = re.search(r"/coupon/issue(?:-async)?/(\d+) ", rd(d, "nginx.log"))
    out = {"coupon_id": int(m.group(1)) if m else None, "quantity": int(rd(d, "stock-before.txt")), "requests": k["requests"], "rate": k["rate"], "duration_s": k["duration_s"],
           "issued": int(rd(d, "issued-db.txt")), "completion_p95_ms": k["completion_p95_ms"], "completion_p99_ms": k["completion_p99_ms"],
           "completion_count": k["completion_count"], "accept_p95_ms": k["accept_p95_ms"], "results": k["results"],
           "dropped_iterations": k["dropped_iterations"], "max_vus": k["max_vus"], "backend_requests": s["backend_requests"], "nginx_status": s["nginx_status"],
           "redis_stock_before": rd(d, "stock-before.txt"), "redis_stock_after": rd(d, "stock-after.txt"), "redis_issued_users": rd(d, "issued-users-redis.txt"),
           "path": "POST /coupon/issue-async/{id} (Redis Lua + Stream consumer) then GET /coupon/issue-async/{requestId}/status until SUCCESS",
           "completion_definition": "request start to the first status poll that returns SUCCESS (polled every 100 ms); a 200 answer counts its own response time", "start": rd(d, "start.txt") or None}
    out["coupon_stages"] = stage_delta(os.path.join(d, "coupon-stages-before.tsv"), os.path.join(d, "coupon-stages.tsv"), "coupon_stage_seconds")
    slow, suppressed = {}, {}
    for p in sorted(glob.glob(os.path.join(d, "redis-slow-*.log"))):
        name = os.path.basename(p)[len("redis-slow-"):-len(".log")]
        text = read(p)
        slow[name] = text.count("[COUPON_REDIS_SLOW]")
        suppressed[name] = sum(int(x) for x in re.findall(r"\[COUPON_REDIS_SLOW\].*suppressed=(\d+)", text))
    out["redis_slow_lines"], out["redis_slow_suppressed"] = slow, suppressed
    out.update(condition(d))
    return out


def num(d, name):
    v = rd(d, name)
    return int(v) if v.lstrip("-").isdigit() else None


def coupon_summary(d, code):
    """r127 쿠폰 측정 요약 - k6 결과 개수 · 최종 응답까지의 시간 · PostgreSQL 에서 센 발급 · 중복 행 · 연결 최대 · 깨우기 출처 · Redis 명령 수"""
    k = json.load(open(os.path.join(d, "k6-summary.json")))
    res = k["results"]
    # 깨우기 줄은 "<시각>\t<백엔드>\t<이름>{...source=\"x\",...} <값>" - 출처별로 백엔드를 합친다(기본 코드는 줄이 없다)
    wake = {}
    for line in rd(d, "coupon-wake.tsv").splitlines():
        m = re.search(r'source="(\w+)".*\s([\d.]+)$', line)
        if m:
            wake[m.group(1)] = wake.get(m.group(1), 0) + int(float(m.group(2)))
    samples = [int(x) for x in rd(d, "db-connections.txt").split() if x.isdigit()]
    out = {"code": code, "servers": condition(d)["servers"], "rate": k["rate"], "duration_s": k["duration_s"], "requests": k["requests"]}
    for key in ("sync_200", "accepted_202", "sold_out_409", "duplicate", "unavailable_503", "other", "success", "failed", "timeout"):
        out[key] = res.get(key, 0)
    out.update({
        "time_to_success_ms": {"p50": k.get("completion_p50_ms"), "p95": k["completion_p95_ms"], "p99": k["completion_p99_ms"]},
        "time_to_success_definition": "request start to the final answer: the 200 response time for a synchronous answer, the first status poll that returns SUCCESS for a 202",
        "completion_count": k["completion_count"], "issued_rows": num(d, "issued-db.txt"), "quantity": num(d, "stock-before.txt"), "duplicate_rows": num(d, "duplicate-rows.txt"),
        "db_connections_peak": max(samples) if samples else None, "db_connections_samples": len(samples),
        "wake": wake, "fallback": wake.get("fallback", 0),
        "redis_commands": {n: int(c) for n, c in re.findall(r"cmdstat_(\w+):calls=(\d+)", rd(d, "redis-commandstats.txt"))},
    })
    out["redis_commands_total"] = sum(out["redis_commands"].values())
    return out


def coupon(d, out, others):
    res = coupon_one(d)
    keep = ("rate", "duration_s", "requests", "issued", "completion_p95_ms", "completion_p99_ms", "accept_p95_ms", "results", "dropped_iterations", "max_vus", "coupon_stages", "redis_slow_lines", "redis_slow_suppressed")
    if others:
        res["other_rates"] = {label: {k: coupon_one(p)[k] for k in keep} for label, p in others}
        res["note"] = "coupon.json is the main run (the lowest rate); the other runs of the same coupon path are listed under other_rates"
    json.dump(res, open(out, "w"), indent=2)
    print({x: res[x] for x in ("requests", "quantity", "issued", "rate", "completion_p95_ms")})


def detail(d, out, background, background_source=None):
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
        "db_body_loads_background_source": (background_source or background) if background else None,
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


def all_scenarios(root, out_dir):
    """raw root 의 배치에서 네 JSON 을 만든다 - 없는 시나리오 폴더는 건너뛰고 한 줄 알린다"""
    os.makedirs(out_dir, exist_ok=True)
    p = lambda *x: os.path.join(root, *x)
    if os.path.isdir(p("flash", "tiny")) or glob.glob(p("flash", "*", "step-*")):
        flash(p("flash"), os.path.join(out_dir, "result.json"))
    else:
        print("skipped result.json: no flash/tiny or flash/<label>/step-* folder under %s" % root)
    runs = []
    for d in glob.glob(p("coupon-*", "coupon")):
        runs.append((json.load(open(os.path.join(d, "k6-summary.json")))["rate"], d))
    runs.sort()
    background = None
    if runs:
        main_dir = runs[0][1]
        others = [(os.path.basename(os.path.dirname(d))[len("coupon-"):], d) for _, d in runs[1:]]
        coupon(main_dir, os.path.join(out_dir, "coupon.json"), others)
        background = os.path.join(main_dir, "pgss.tsv")
    else:
        print("skipped coupon.json: no coupon-*/coupon folder under %s" % root)
    if os.path.isdir(p("detail", "detail")):
        bg = background if background and os.path.isfile(background) else None
        detail(p("detail", "detail"), os.path.join(out_dir, "detail.json"), bg, os.path.relpath(bg, root) if bg else None)
    else:
        print("skipped detail.json: no detail/detail folder under %s" % root)
    if os.path.isdir(p("pgcancel", "pgcancel")):
        pgcancel(p("pgcancel", "pgcancel"), os.path.join(out_dir, "pg-cancel.json"))
    else:
        print("skipped pg-cancel.json: no pgcancel/pgcancel folder under %s" % root)


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    sub, src, out = argv[1:4]
    rest = argv[4:]
    if sub == "all":
        all_scenarios(src, out)
    elif sub == "flash":
        flash(src, out)
    elif sub == "coupon":
        coupon(src, out, [tuple(x.split("=", 1)) for x in rest if x != "--other" and "=" in x])
        # CODE 환경 변수(base · new)는 요약에 적는 실행의 코드 이름이다 - 없으면 null 로 두어 잘못 채우지 않는다
        json.dump(coupon_summary(src, os.environ.get("CODE")), open(os.path.join(os.path.dirname(os.path.abspath(out)), "summary.json"), "w"), indent=2)
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
