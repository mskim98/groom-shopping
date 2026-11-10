#!/usr/bin/env python3
"""단계 폴더(run.sh tiny · step 이 만든 <out-dir>/tiny · <out-dir>/step-<rps>)를 result.json 한 개로 모은다

usage: collect.py <out-dir> <result.json>
표준 라이브러리만 쓴다. 숫자는 폴더 안의 파일에서만 읽는다(k6-summary.json · db_window_*.txt · k6cpu.tsv · offsets_*.txt · lag.log · ledger-*.log · nginx.log · pgss.tsv · stats.tsv · prom.tsv)
"""
import glob
import json
import os
import re
import subprocess
import sys

ENDPOINT_URIS = {"/api/v1/cart/add": "cart_add", "/api/v1/order": "order", "/api/v1/payment/confirm": "confirm"}
PARTITIONS = 12


def read(path, default=""):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return default


def percentile(sorted_vals, p):
    # 가장 가까운 순위 방식
    if not sorted_vals:
        return 0
    k = max(0, min(len(sorted_vals) - 1, int(-(-p * len(sorted_vals) // 100)) - 1))
    return sorted_vals[k]


def offsets(path):
    out = [0] * PARTITIONS
    for line in read(path).splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0].isdigit() and int(parts[0]) < PARTITIONS:
            out[int(parts[0])] = int(parts[1])
    return out


def lag_max(path):
    # lag.log 는 `## <시각>` 로 시작하는 kafka-consumer-groups --describe 출력 묶음의 이어붙임이다
    mx = [0] * PARTITIONS
    for line in read(path).splitlines():
        f = line.split()
        if len(f) >= 6 and f[1] == "payment-events" and f[2].isdigit() and f[5].isdigit() and int(f[2]) < PARTITIONS:
            mx[int(f[2])] = max(mx[int(f[2])], int(f[5]))
    return mx


def ledger(folder):
    ms = []
    for p in glob.glob(os.path.join(folder, "ledger-*.log")):
        for m in re.finditer(r"products=(\d+) rows=(\d+) elapsedMs=(\d+)", read(p)):
            ms.append(int(m.group(3)))
    ms.sort()
    return {"p50": percentile(ms, 50), "p95": percentile(ms, 95), "max": ms[-1] if ms else 0, "cycles": len(ms)}


def backend_requests(folder):
    ip_to_name = {}
    for line in read(os.path.join(folder, "backends.tsv")).splitlines():
        f = line.split()
        if len(f) == 2:
            ip_to_name[f[1]] = f[0]
    counts = {"backend1": 0, "backend2": 0, "backend3": 0}
    status = {}
    unknown = 0
    for line in read(os.path.join(folder, "nginx.log")).splitlines():
        f = line.split(None, 5)
        if len(f) < 6 or f[2] not in ENDPOINT_URIS:
            continue
        cls = "5xx" if f[3].startswith("5") and f[3] != "503" else f[3]
        status[cls] = status.get(cls, 0) + 1
        # 재시도가 있으면 $upstream_addr 가 "ip:8080, ip:8080" 이다 - 마지막 주소가 응답한 쪽이다
        rest = f[5].rsplit(None, 1)[0] if " " in f[5] else f[5]
        ip = rest.split(",")[-1].strip().split(":")[0]
        name = ip_to_name.get(ip)
        if name:
            counts[name] += 1
        else:
            unknown += 1
    return counts, status, unknown


def pgss(path):
    top = []
    for line in read(path).splitlines():
        f = line.split("\t")
        if len(f) == 4:
            top.append({"query": f[0], "calls": int(f[1]), "mean_ms": float(f[2]), "total_ms": float(f[3])})
    return top


def stats_peak(path):
    peak = {}
    for line in read(path).splitlines():
        f = line.split("\t")
        if len(f) != 4:
            continue
        name, cpu, mem = f[1], f[2].rstrip("%"), f[3].split("/")[0].strip()
        m = re.match(r"([\d.]+)\s*(B|KiB|MiB|GiB)", mem)
        mib = float(m.group(1)) * {"B": 1 / 1048576, "KiB": 1 / 1024, "MiB": 1, "GiB": 1024}[m.group(2)] if m else 0
        try:
            cpu_v = float(cpu)
        except ValueError:
            continue
        p = peak.setdefault(name, {"cpu_pct_peak": 0.0, "cpu_pct_samples": [], "mem_mib_peak": 0.0})
        p["cpu_pct_peak"] = max(p["cpu_pct_peak"], cpu_v)
        p["cpu_pct_samples"].append(cpu_v)
        p["mem_mib_peak"] = max(p["mem_mib_peak"], mib)
    for p in peak.values():
        s = sorted(p.pop("cpu_pct_samples"))
        p["cpu_pct_median"] = percentile(s, 50)
        p["cpu_pct_peak"] = round(p["cpu_pct_peak"], 1)
        p["mem_mib_peak"] = round(p["mem_mib_peak"], 1)
    return peak


def prom_peak(path):
    # Hikari 대기 · 사용 중 연결의 최댓값, bulkhead 남은 칸의 최솟값, 프로세스 CPU 사용률의 최댓값(백엔드별)
    out = {}
    for line in read(path).splitlines():
        f = line.split("\t", 2)
        if len(f) != 3:
            continue
        backend, metric_line = f[1], f[2]
        m = re.match(r"([a-z_0-9]+)(\{[^}]*\})?\s+([-\d.eE+]+)$", metric_line)
        if not m:
            continue
        name, val = m.group(1), float(m.group(3))
        b = out.setdefault(backend, {})
        if name == "hikaricp_connections_pending":
            b["hikari_pending_max"] = max(b.get("hikari_pending_max", 0), val)
        elif name == "hikaricp_connections_active":
            b["hikari_active_max"] = max(b.get("hikari_active_max", 0), val)
        elif name == "hikaricp_connections_max":
            b["hikari_max"] = val
        elif name == "hikaricp_connections_timeout_total":
            b["hikari_timeout_total_last"] = val
        elif name == "resilience4j_bulkhead_available_concurrent_calls":
            key = "bulkhead_available_min_" + (re.search(r'name="([^"]+)"', m.group(2) or "") or [None, "x"])[1]
            b[key] = min(b.get(key, 1e9), val)
        elif name == "process_cpu_usage":
            b["process_cpu_usage_max"] = max(b.get("process_cpu_usage_max", 0), val)
    return out


def orders(folder):
    """시험 창(db_window_start/end.txt, DB 시계 · Asia/Seoul) 안에 만들어진 주문 수와 그 주문의 order_item 행 수 - 데이터베이스에서 센다"""
    start = read(os.path.join(folder, "db_window_start.txt")).strip()
    end = read(os.path.join(folder, "db_window_end.txt")).strip()
    cached = os.path.join(folder, "orders.json")
    if not start or not end:
        try:
            return json.loads(read(cached))
        except ValueError:
            return {}
    env = {}
    for line in read(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".env")).splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    sql = ("SELECT (SELECT count(*) FROM orders WHERE created_at >= '%(s)s' AND created_at <= '%(e)s'), "
           "(SELECT count(*) FROM order_item i JOIN orders o ON o.id = i.order_id WHERE o.created_at >= '%(s)s' AND o.created_at <= '%(e)s')") % {"s": start, "e": end}
    cmd = ["docker", "exec", "db", "psql", "-U", env.get("POSTGRES_USER", ""), "-d", "shop_seed_m1", "-Atc", sql]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=60).stdout.strip()
        created, items = out.split("|")
        result = {"created": int(created), "items": int(items), "window": [start, end]}
        with open(cached, "w") as f:
            json.dump(result, f)
        return result
    except (subprocess.SubprocessError, ValueError, OSError):
        # 컨테이너가 내려간 뒤 폴더만 다시 모을 때는 앞서 센 값(orders.json)을 쓴다
        try:
            return json.loads(read(cached))
        except ValueError:
            return {}


def hikari(folder):
    """prom.tsv 의 누적 시계열에서 백엔드별 Hikari 대기 · 사용 · 획득 시간 - usage/acquire 는 첫 표본과 마지막 표본의 합 · 횟수 차이로 평균을 낸다"""
    series = {}
    for line in read(os.path.join(folder, "prom.tsv")).splitlines():
        f = line.split("\t", 2)
        if len(f) != 3:
            continue
        m = re.match(r"hikaricp_connections_(usage|acquire)_seconds_(count|sum|max)(\{[^}]*\})?\s+([-\d.eE+]+)$", f[2])
        if m:
            series.setdefault(f[1], {}).setdefault(m.group(1) + "_" + m.group(2), []).append(float(m.group(4)))
    out = {}
    for backend, d in series.items():
        o = {}
        for kind in ("usage", "acquire"):
            cnt, sm, mx = d.get(kind + "_count", []), d.get(kind + "_sum", []), d.get(kind + "_max", [])
            if len(cnt) >= 2 and cnt[-1] > cnt[0]:
                o[kind + "_mean_ms"] = round((sm[-1] - sm[0]) / (cnt[-1] - cnt[0]) * 1000, 2)
                o[kind + "_count"] = int(cnt[-1] - cnt[0])
            if mx:
                o[kind + "_max_s_peak"] = round(max(mx), 3)
        out[backend] = o
    return out


def k6_cpu(path):
    vals = []
    for line in read(path).splitlines():
        f = line.split("\t")
        if len(f) == 2:
            try:
                vals.append(float(f[1]))
            except ValueError:
                pass
    s = sorted(vals)
    return {"samples": len(vals), "peak_pct": max(vals) if vals else None, "median_pct": percentile(s, 50) if vals else None}


def sampler_gaps(folder):
    """표본 사이 간격(초)의 중앙값 - 보고서가 표본 주기를 실제 값으로 쓴다"""
    from datetime import datetime
    out = {}
    for name, fn in (("docker_stats", "stats.tsv"), ("prometheus", "prom.tsv"), ("k6_cpu", "k6cpu.tsv")):
        ts = sorted({l.split("\t")[0] for l in read(os.path.join(folder, fn)).splitlines() if l})
        t = [datetime.strptime(x, "%Y-%m-%dT%H:%M:%SZ").timestamp() for x in ts if re.match(r"\d{4}-", x)]
        gaps = sorted(b - a for a, b in zip(t, t[1:]))
        out[name] = percentile(gaps, 50) if gaps else None
    ts = [l[3:] for l in read(os.path.join(folder, "lag.log")).splitlines() if re.match(r"## \d{4}-", l)]
    t = [datetime.strptime(x, "%Y-%m-%dT%H:%M:%SZ").timestamp() for x in ts]
    gaps = sorted(b - a for a, b in zip(t, t[1:]))
    out["consumer_lag"] = percentile(gaps, 50) if gaps else None
    return out


def collect_step(folder, target):
    k6 = json.loads(read(os.path.join(folder, "k6-summary.json"), "{}") or "{}")
    before, after = offsets(os.path.join(folder, "offsets_before.txt")), offsets(os.path.join(folder, "offsets_after.txt"))
    msgs = [a - b for a, b in zip(after, before)]
    be, nginx_status, unknown = backend_requests(folder)
    m = re.search(r"setup: logins=(\d+) carts_cleared=(\d+) extra_requests=(\d+)", read(os.path.join(folder, "k6.log")))
    step = {
        "target_rps": target,
        "achieved_rps": k6.get("achieved_rps"),
        "dropped_iterations": k6.get("dropped_iterations"),
        "iterations": k6.get("iterations"),
        "endpoints": k6.get("endpoints", {}),
        "errors": k6.get("errors", {}),
        "backend_requests": be,
        "nginx_status_all_phases": nginx_status,
        "nginx_unmatched_upstream": unknown,
        "partitions": {"messages": msgs, "max_min_ratio": (max(msgs) / min(msgs)) if min(msgs) > 0 else None},
        "lag": {"max_per_partition": lag_max(os.path.join(folder, "lag.log"))},
        "ledger_apply_ms": ledger(folder),
        "pgss_top": pgss(os.path.join(folder, "pgss.tsv")),
        "docker_stats": stats_peak(os.path.join(folder, "stats.tsv")),
        "orders": orders(folder),
        "extra_requests": k6.get("extra_requests", {}),
        "hikari_timers": hikari(folder),
        "k6_cpu": k6_cpu(os.path.join(folder, "k6cpu.tsv")),
        "sampler_median_gap_s": sampler_gaps(folder),
        "backend_metrics": prom_peak(os.path.join(folder, "prom.tsv")),
        "waits": dict(l.split("=", 1) for l in read(os.path.join(folder, "wait.txt")).split() if "=" in l),
        "setup": {"logins": int(m.group(1)), "carts_cleared": int(m.group(2)), "extra_requests": int(m.group(3))} if m else None,
        "max_vus": k6.get("max_vus"),
        "window": {"start": read(os.path.join(folder, "start.txt")).strip(), "end": read(os.path.join(folder, "end.txt")).strip()},
    }
    return step


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    out_dir, result = argv[1], argv[2]
    result_obj = {}
    if os.path.isdir(os.path.join(out_dir, "tiny")):
        result_obj["tiny"] = collect_step(os.path.join(out_dir, "tiny"), 50)
    steps = []
    for d in sorted(glob.glob(os.path.join(out_dir, "step-*")), key=lambda p: int(p.rsplit("-", 1)[1])):
        steps.append(collect_step(d, int(d.rsplit("-", 1)[1])))
    result_obj["steps"] = steps
    os.makedirs(os.path.dirname(os.path.abspath(result)), exist_ok=True)
    with open(result, "w") as f:
        json.dump(result_obj, f, ensure_ascii=False, indent=2)
    print("wrote %s (tiny=%s, steps=%d)" % (result, "tiny" in result_obj, len(steps)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
