#!/usr/bin/env python3
"""r129 실행 하나의 원시 폴더에서 summary.json 을 만든다 - 숫자 계산은 derive_r129.py(시험이 있다), 여기는 파일을 읽어 이어 붙이는 일만 한다

usage: r129-summary.py <coupon|hotrow|bulkhead|ratelimit|outbox|comp> <raw dir> <out summary.json>
"""
import importlib.util
import json
import os
import re
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import derive_r129 as d  # noqa: E402

_spec = importlib.util.spec_from_file_location("derive_final", os.path.join(HERE, "derive-final.py"))
final = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(final)


def rd(dirpath, name, default=""):
    try:
        with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return default


def jrd(dirpath, name, default=None):
    t = rd(dirpath, name)
    return json.loads(t) if t.strip() else default


def intf(dirpath, name):
    t = rd(dirpath, name).strip()
    return int(t) if t.lstrip("-").isdigit() else None


def iso(epoch):
    return datetime.fromtimestamp(float(epoch), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def common(meta, cond, host_top, prom):
    return {"servers": cond.get("servers"), "commit": meta["base_commit"], "tooling_commit": meta["tooling_commit"],
            "host_idle_pct": d.host_idle(host_top), "hikari": d.hikari_peaks(prom)}


def step_dir(raw):
    return next(os.path.join(raw, n) for n in sorted(os.listdir(raw)) if n.startswith("step-"))


def redis_usec(text):
    return {n: {"calls": int(c), "usec_per_call": float(u)} for n, c, u in re.findall(r"cmdstat_(\w+):calls=(\d+),usec=\d+,usec_per_call=([\d.]+)", text)}


def status_span(nginx, status, p_end, u_start):
    """쿠폰 발급 요청 중 이 상태 코드로 끝난 것의 건수와, 정지 시작 · 해제 시각 기준 첫 · 마지막 응답 시각(초, nginx 로그 시각은 초 단위)"""
    ts = [d._utc(l.split()[0]) for l in nginx.splitlines() if len(l.split()) >= 4 and "/coupon/issue/" in l.split()[2] and l.split()[3] == status]
    if not ts:
        return {"count": 0}
    return {"count": len(ts), "first_s_after_pause": round(min(ts) - p_end, 1), "last_s_after_pause": round(max(ts) - p_end, 1),
            "last_s_after_unpause": round(max(ts) - u_start, 1)}


def diag(raw):
    """진단 표본(diag-prom.tsv · diag-cpu.tsv)에서 GC 정지 합 · 최대 정지 · CPU 최댓값 · 힙 최댓값 · 표본 간격이 2 초를 넘은 구간(앱이 응답하지 못한 흔적)"""
    gc_sum, gc_cnt, gc_max, proc, sysc, heap, thr = [], [], [], [], [], [], []
    stamps = []
    for line in rd(raw, "diag-prom.tsv").splitlines():
        f = line.split("\t", 1)
        m = re.match(r"(\w+)(\{[^}]*\})?\s+([-\d.eE+]+)$", f[1]) if len(f) == 2 else None
        if not m:
            continue
        v = float(m.group(3))
        {"jvm_gc_pause_seconds_sum": gc_sum, "jvm_gc_pause_seconds_count": gc_cnt, "jvm_gc_pause_seconds_max": gc_max, "process_cpu_usage": proc,
         "system_cpu_usage": sysc, "jvm_memory_used_bytes": heap, "jvm_threads_live_threads": thr}.get(m.group(1), []).append(v)
        if m.group(1) == "process_cpu_usage":
            stamps.append(float(f[0]))
    gaps = [(round(a, 2), round(b - a, 2)) for a, b in zip(stamps, stamps[1:]) if b - a > 2.0]
    cpu = [l.split("\t") for l in rd(raw, "diag-cpu.tsv").splitlines() if l.count("\t") == 2]
    return {"samples": len(stamps), "gc_pause_total_s": round(max(gc_sum) - min(gc_sum), 3) if gc_sum else None, "gc_pauses": int(max(gc_cnt) - min(gc_cnt)) if gc_cnt else None,
            "gc_pause_max_s": max(gc_max) if gc_max else None, "process_cpu_peak": max(proc) if proc else None, "system_cpu_peak": max(sysc) if sysc else None,
            "heap_used_peak_mb": round(max(heap) / 1048576) if heap else None, "threads_live_peak": int(max(thr)) if thr else None,
            "sample_gaps_over_2s": gaps, "k6_cpu_pct_peak": max(float(c[1]) for c in cpu) if cpu else None, "docker_vm_cpu_pct_peak": max(float(c[2]) for c in cpu) if cpu else None}


def coupon(raw, meta):
    k6 = jrd(raw, "k6-summary.json")
    cond = final.condition(raw)
    s = final.coupon_summary(raw, None)
    s.update(common(meta, cond, rd(raw, "host-top.txt"), rd(raw, "prom.tsv")))
    s.update({"run": meta["run"], "strategy": meta["strategy"], "issue_path": meta["issue_path"], "auth": meta.get("auth", "token"), "bypass_user_id_check": rd(raw, "bypass-user-id-check.tsv").strip() or None,
              "answer_ms_by_outcome": d.answer_ms_by_outcome(k6),
              "db_calls": d.db_calls(d.parse_pgss(rd(raw, "pgss-end.tsv")), k6["requests"], d.parse_pgss(rd(raw, "pgss-setup.tsv")) if rd(raw, "pgss-setup.tsv") else None),
              "redis_usec_per_call": redis_usec(rd(raw, "redis-commandstats.txt")),
              "redis_slowlog_entries": len([1 for l in rd(raw, "redis-slowlog.txt").splitlines() if re.match(r"\d+\) \(integer\) \d+$", l.strip())]),
              "dropped_iterations": k6["dropped_iterations"], "max_vus": k6["max_vus"],
              "answer_definition": "request start to the final answer: 200 response time (issued), 409 response time (sold_out / duplicate), the first SUCCESS poll for a 202; other = 503, failures, timeouts, anything else"})
    cl = rd(raw, "redis-slow-backend1.log")
    s["redis_slow_lines"] = cl.count("[COUPON_REDIS_SLOW]")
    for name in ("redis-latency-latest.txt", "redis-latency-history.txt", "redis-info-stats.txt"):
        if rd(raw, name):
            s.setdefault("diag_files", {})[name] = rd(raw, name).strip()[:2000]
    if meta.get("diag"):
        s["diag"] = diag(raw)
    f = jrd(raw, "fault.json")
    if f:
        p_end, u_start = f["pause_cmd_end"], f["unpause_cmd_start"]
        nginx = rd(raw, "nginx.log")
        cb = rd(raw, "backend-cb.log")
        lo, hi = iso(int(p_end)), iso(int(u_start) + 1)  # nginx 로그 시각은 초 단위라 창을 바깥 초로 넓힌다(양쪽 1 초 안쪽 오차)
        s["fault"] = {
            "kind": f["kind"], "paused_ms": round((u_start - p_end) * 1000), "pause_start_utc": iso(p_end), "unpause_utc": iso(u_start),
            "pause_start_after_load_start_s": round(p_end - float(rd(raw, "load-start.epoch") or p_end), 2),
            "circuit": {"name": "redis-coupon", "transitions": d.breaker_transitions(cb, "redis-coupon", datetime.fromtimestamp(p_end, timezone.utc).isoformat())},
            "answers_during_pause": d.answers_in_window(nginx, "/coupon/issue/", lo, hi),
            "answers_before_pause": d.answers_in_window(nginx, "/coupon/issue/", "1970-01-01T00:00:00Z", lo),
            "answers_after_pause": d.answers_in_window(nginx, "/coupon/issue/", hi, "2999-01-01T00:00:00Z"),
            "status_401": status_span(nginx, "401", p_end, u_start),
            "db_fallback_log_lines": intf(raw, "fallback-count.txt"),
            "window_note": "answers counted by nginx log second of the response end, window widened to whole seconds",
        }
    return s


def hotrow(raw, meta):
    sd = step_dir(raw)
    k6 = jrd(sd, "k6-summary.json")
    cond = jrd(sd, "condition.json", {})
    ep = k6["endpoints"]["confirm"]
    before = rd(raw, "product-before.tsv").strip().split("\t")
    st_before, st_after = int(before[1]), int(rd(raw, "stock-after.txt").strip())
    led = intf(raw, "ledger-sale-after.txt") - intf(raw, "ledger-sale-before.txt")
    conf = intf(raw, "confirmed-after.txt") - intf(raw, "confirmed-before.txt")
    s = common(meta, cond, rd(sd, "host-top.txt"), rd(sd, "prom.tsv"))
    s.update({
        "run": meta["run"], "flash_sale": meta["flash_sale"], "product_count": intf(raw, "product-count.txt"), "target_rps": meta["rps"], "ramp_s": meta["ramp_s"], "hold_s": meta["hold_s"],
        "product_flash_sale_in_db": before[0] == "t",
        "confirm_ms": {"p50": ep["p50"], "p95": ep["p95"], "p99": ep["p99"]},
        "confirmed_per_s": round((ep["status"]["2xx"] - k6.get("accepted_202", 0)) / meta["hold_s"], 2), "accepted_202": k6.get("accepted_202", 0),
        "achieved_rps": k6["achieved_rps"], "requests_hold": k6["errors"]["requests_hold"], "error_rate": k6["errors"]["rate"], "status": ep["status"],
        "dropped_iterations": k6["dropped_iterations"], "iterations_skipped": k6["iterations_skipped"],
        "lock_wait_samples": d.lock_wait_samples(rd(raw, "pg-activity.tsv")),
        "hikari_pending_peak": d.hikari_peaks(rd(sd, "prom.tsv"))["pending_peak"],
        "stock_update_statement": d.stock_update_stats((rd(raw, "pgss-stock-update.tsv") or rd(sd, "pgss.tsv")).splitlines()),
        "stock_update_statement_source": "pgss-stock-update.tsv" if rd(raw, "pgss-stock-update.tsv") else "pgss.tsv (top 20 by total time; absent when the statement is below it)",
        "db_connections_peak": None,
        "path_check": d.hotrow_path_check(meta["flash_sale"], led, conf, st_before, st_after),
        "path_evidence": {"ledger_sale_rows_added": led, "confirmed_payments_added": conf, "stock_before": st_before, "stock_after": st_after},
        "consumer_writers_on_row": "payment-events has 2 partitions, concurrency 2 per server: at most 2 consumer threads run the conditional stock UPDATE (flash_sale=false); flash_sale=true takes stock in Redis Lua and the request threads INSERT ledger rows",
    })
    return s


def bulkhead_available_min(prom):
    v = [float(m.group(1)) for m in (re.search(r'resilience4j_bulkhead_available_concurrent_calls\{name="toss"\}\s+([\d.]+)', l) for l in prom.splitlines()) if m]
    return int(min(v)) if v else None


def ms_above(journal, limit):
    ev = []
    for e in journal.get("requests", []):
        if e["request"]["url"].endswith("/v1/payments/confirm") and e.get("timing"):
            a = e["request"]["loggedDate"]
            ev += [(a, 1), (a + e["timing"]["totalTime"], -1)]
    cur = prev = over = 0
    for t, dlt in sorted(ev):
        if cur > limit:
            over += t - prev
        cur += dlt
        prev = t
    return over


def bulkhead(raw, meta):
    sd = step_dir(raw)
    k6 = jrd(sd, "k6-summary.json")
    cond = jrd(sd, "condition.json", {})
    journal = jrd(raw, "wiremock-requests.json", {"requests": []})
    confirms = [e for e in journal["requests"] if e["request"]["url"].endswith("/v1/payments/confirm")]
    s = common(meta, cond, rd(sd, "host-top.txt"), rd(sd, "prom.tsv"))
    s.update({
        "run": meta["run"], "toss_delay_ms": meta["toss_delay_ms"], "vus": meta["vus"], "hold_s": meta["hold_s"],
        "toss_concurrent_peak": d.toss_concurrent_peak(journal), "toss_calls": len(confirms),
        "pg_busy": k6["by_code"].get("PAYMENT_PG_BUSY", 0), "by_code": k6["by_code"],
        "confirm_ms_by_status": {c: {"count": v["count"], "p50": v["p50"], "p95": v["p95"]} for c, v in k6["by_status"].items()},
        "sent": k6["sent"], "orders_available": k6["orders_available"], "iterations_skipped": k6["iterations_skipped"],
        "hikari_pending_peak": d.hikari_peaks(rd(sd, "prom.tsv"))["pending_peak"],
        "bulkhead": {"max_concurrent_calls": 60, "max_wait_duration_s": 10, "error_code": "PAYMENT_PG_BUSY (503)"},
        # 앱 쪽 근거 - 격벽 남은 자리 게이지(5 초 표본)의 최솟값, 그리고 일지 기준 동시 호출이 한도를 넘은 시간(ms · 기록 시각이 ms 단위라 생기는 겹침)
        "bulkhead_toss_available_min": bulkhead_available_min(rd(sd, "prom.tsv")),
        "journal_ms_above_limit": ms_above(journal, 60),
    })
    return s


def ratelimit(raw, meta):
    k6 = jrd(raw, "k6-summary.json")
    limit = d.parse_limit(rd(raw, "limit-lines.txt"))
    c = d.ratelimit_counts(k6["status_counts"])
    rate_n = int(re.match(r"(\d+)r/s", limit["rate"]).group(1))
    nginx = [l.split() for l in rd(raw, "nginx.log").splitlines() if "/api/v1/product" in l]
    ips = sorted({f[-1] for f in nginx if f})
    stat = {}
    for f in nginx:
        stat[f[3]] = stat.get(f[3], 0) + 1
    s = {"run": meta["run"], "servers": jrd(raw, "condition.json", {}).get("servers", 1), "commit": meta["base_commit"], "tooling_commit": meta["tooling_commit"],
         "host_idle_pct": d.host_idle(rd(raw, "host-top.txt")),
         "rate": meta["rate"], "duration_s": meta["duration_s"], "limit": limit, **c, "status_counts": k6["status_counts"],
         "expected_pass_nginx_model": d.expected_pass(rate_n, limit["burst"], meta["rate"], meta["duration_s"]),
         "expected_pass_simple": limit["burst"] + 1 + rate_n * meta["duration_s"],
         "nginx_log_status": stat, "source_ips": ips, "dropped_iterations": k6["dropped_iterations"],
         "limit_lines": rd(raw, "limit-lines.txt").splitlines()}
    return s


def counts_of(text):
    v = [int(x) for x in text.split()]
    return {"consumed": v[0], "skipped": v[1]} if len(v) == 2 else None


def outbox(raw, meta):
    sd = step_dir(raw)
    k6 = jrd(sd, "k6-summary.json")
    cond = jrd(sd, "condition.json", {})
    b, a = jrd(raw, "side-effects-before.json"), jrd(raw, "side-effects-after.json")
    cb, ca = counts_of(rd(raw, "log-counts-before.txt")), counts_of(rd(raw, "log-counts-after.txt"))
    s = common(meta, cond, rd(sd, "host-top.txt"), rd(sd, "prom.tsv"))
    s.update({
        "run": meta["run"], "confirms": meta["confirms"], "confirm_by_status": {c: v["count"] for c, v in k6["by_status"].items()},
        "payments_done": b["payments_done"], "republished": intf(raw, "republished.txt"),
        "consumed_after_republish": d.consumed_after_republish(rd(raw, "cg-before.txt"), rd(raw, "cg-after.txt")),
        "side_effects_before": b, "side_effects_after": a, **{("side_effect_" + k): v for k, v in d.side_effect_delta(b, a).items()},
        "consumer_log_counts_before": cb, "consumer_log_counts_after": ca,
        "consumer_log_delta": {k: ca[k] - cb[k] for k in ca} if ca and cb else None,
        "republish_wait_s": intf(raw, "republish-wait-s.txt"),
        "outbox_status_before": rd(raw, "outbox-status-before.tsv").strip(), "outbox_status_after": rd(raw, "outbox-status-after.tsv").strip(),
    })
    return s


def comp(raw, meta):
    sd = step_dir(raw)
    k6 = jrd(sd, "k6-summary.json")
    cond = jrd(sd, "condition.json", {})
    journal = jrd(raw, "wiremock-requests.json", {"requests": []})
    c = d.compensation(journal)
    orders = {l.split("\t")[0]: int(l.split("\t")[1]) for l in rd(raw, "order-states.tsv").splitlines() if "\t" in l}
    s = common(meta, cond, rd(sd, "host-top.txt"), rd(sd, "prom.tsv"))
    s.update({
        "run": meta["run"], "confirms": meta["confirms"], "confirm_by_status": {k: v["count"] for k, v in k6["by_status"].items()}, "confirm_by_code": k6["by_code"],
        **c, "orders_confirmed": orders.get("CONFIRMED", 0), "trigger_installed": intf(raw, "trigger-count.txt"),
        "payment_states": rd(raw, "payment-states.tsv").strip(), "order_states": rd(raw, "order-states.tsv").strip(), "compensation_states": rd(raw, "compensation-states.tsv").strip(),
        "cancel_count_series": [l.split("\t") for l in rd(raw, "cancel-count.tsv").splitlines()],
        "wait_note": rd(raw, "comp-wait-note.txt").strip() or "cancel count unchanged for 60 s (2 compensation cycles of 30 s)",
    })
    return s


# 필수 원시 파일 - 없으면 요약을 만들지 않고 파일 이름을 대며 오류로 끝난다. "step/" 로 시작하는 것은 run.sh step 이 만든 <raw>/step-<n>/ 안의 파일이다
# 선택(없으면 해당 키만 비는 것으로 문서화된 읽기): pgss-stock-update.tsv(재고 UPDATE 문장, flash 3회는 없다 - R129-12-3) · pgss-setup.tsv(준비 단계 끝 표본, 없으면 setup 제외 키 없음) ·
#   coupon-wake.tsv(스트림 경로만) · fault.json · backend-cb.log · fallback-count.txt(정지 실행만) · redis-latency-*.txt · redis-info-stats.txt · diag-*.tsv(진단 실행만) · bypass-user-id-check.tsv ·
#   redis-slow-backend1.log · redis-slowlog.txt · load-start.epoch(정지 실행만) · comp-wait-note.txt · outbox-status-*.tsv · republish-wait-s.txt
STEP_BASE = ["step/condition.json", "step/host-top.txt", "step/prom.tsv", "step/k6-summary.json"]
REQUIRED = {
    "coupon": ["meta.json", "k6-summary.json", "condition.json", "stock-before.txt", "issued-db.txt", "duplicate-rows.txt", "pgss-end.tsv", "redis-commandstats.txt",
               "host-top.txt", "prom.tsv", "db-connections.txt", "coupon-stages.tsv", "coupon-stages-before.tsv", "nginx.log"],
    "hotrow": ["meta.json", "product-before.tsv", "stock-after.txt", "ledger-sale-before.txt", "ledger-sale-after.txt", "confirmed-before.txt", "confirmed-after.txt",
               "product-count.txt", "pg-activity.tsv", "step/pgss.tsv"] + STEP_BASE,
    "bulkhead": ["meta.json", "wiremock-requests.json"] + STEP_BASE,
    "ratelimit": ["meta.json", "k6-summary.json", "limit-lines.txt", "nginx.log", "host-top.txt", "condition.json"],
    "outbox": ["meta.json", "side-effects-before.json", "side-effects-after.json", "cg-before.txt", "cg-after.txt", "republished.txt", "log-counts-before.txt",
               "log-counts-after.txt"] + STEP_BASE,
    "comp": ["meta.json", "wiremock-requests.json", "order-states.tsv", "payment-states.tsv", "compensation-states.tsv", "trigger-count.txt"] + STEP_BASE,
}


def required_files(kind, raw):
    """kind 의 필수 원시 파일의 경로 목록 - step/ 접두는 raw 아래 첫 step-* 폴더(없으면 step-* 자리표시)로 푼다"""
    steps = sorted(n for n in os.listdir(raw) if n.startswith("step-")) if os.path.isdir(raw) else []
    step = steps[0] if steps else "step-*"
    return [os.path.join(raw, step, n[5:]) if n.startswith("step/") else os.path.join(raw, n) for n in REQUIRED[kind]]


def required_missing(kind, raw):
    return [p for p in required_files(kind, raw) if not os.path.isfile(p)]


def main(argv):
    if len(argv) != 4 or argv[1] not in ("coupon", "hotrow", "bulkhead", "ratelimit", "outbox", "comp"):
        print(__doc__)
        return 2
    kind, raw, out = argv[1:]
    missing = required_missing(kind, raw)
    if missing:
        for p in missing:
            print("missing required raw file: %s" % p, file=sys.stderr)
        return 2
    meta = jrd(raw, "meta.json")
    summary = {"coupon": coupon, "hotrow": hotrow, "bulkhead": bulkhead, "ratelimit": ratelimit, "outbox": outbox, "comp": comp}[kind](raw, meta)
    json.dump(summary, open(out, "w"), indent=2, ensure_ascii=False)
    print("wrote %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
