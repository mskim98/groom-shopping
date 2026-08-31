#!/usr/bin/env python3
"""회차 하나의 결과를 원장(rounds.csv) 한 줄로 추가한다

마지막 종합표를 손으로 옮겨 적지 않으려는 것이다. 라운드가 늘면 회차가 수십 개가 되는데,
그때 사람이 옮기면 어느 칸이 어느 회차의 값인지 어긋나도 알아챌 수단이 없다.
"""
import csv
import json
import os
import pathlib

LEDGER = pathlib.Path(os.environ["LEDGER"])
SUMMARY = pathlib.Path(os.environ["SUMMARY"])
RUNTIME = pathlib.Path(os.environ["RUNTIME"])

FIELDS = [
    "date", "strategy", "round", "stock", "vus", "iterations",
    "load_seconds", "rush_seconds", "k6_exit",
    "issue_p95_ms", "issue_p99_ms", "issue_med_ms",
    "http_req_failed_rate", "iterations_per_sec",
    "success", "soldout", "dup", "contention", "mismatch", "pending", "timeout",
    "confirm_p95_ms", "login_med_ms",
    "bystander_ok_rate", "bystander_p95_ms",
    "hikari_active_max", "hikari_pending_max", "hikari_timeout_max", "tomcat_busy_max",
    "assert1_issued", "assert2_dup_users", "assert3_remaining", "assert4_waiting",
    "asserts_pass",
]


def metric(m, name, key, default=""):
    v = m.get(name, {}).get(key)
    return round(v, 2) if isinstance(v, float) else (v if v is not None else default)


def runtime_max(rows, column):
    vals = []
    for r in rows:
        raw = r.get(column, "")
        if raw not in ("", None):
            try:
                vals.append(float(raw))
            except ValueError:
                pass
    return max(vals) if vals else ""


m = json.loads(SUMMARY.read_text())["metrics"]

rows = []
if RUNTIME.exists():
    with RUNTIME.open() as f:
        rows = list(csv.DictReader(f))

issued = int(os.environ["A_ISSUED"] or 0)
dup_users = int(os.environ["A_DUP"] or 0)
remaining = int(os.environ["A_REMAIN"] or 0)
waiting = int(os.environ["A_WAITING"] or 0)
stock = int(os.environ["STOCK"])

# 단언 4종을 하나로 접어 둔다. 어느 것이 깨졌는지는 개별 칸에 그대로 남아 있다
# 미달(issued < stock)도 실패로 센다 - S1 에서는 그것이 예상 결과이고, 예상 결과도 실패로 기록해야
# "무엇을 지켜내지 못했는가"가 표에 남는다
asserts_pass = (issued == stock and dup_users == 0 and remaining == 0 and waiting == 0)

row = {
    "date": os.environ["STAMP"],
    "strategy": os.environ["STRATEGY"],
    "round": os.environ["ROUND"],
    "stock": stock,
    "vus": os.environ["VUS"],
    "iterations": os.environ["ITERATIONS"],
    "load_seconds": os.environ["LOAD_SECONDS"],
    # 벽시계 시간이 아니라 마지막 발급 요청까지의 경과 시간이다
    "rush_seconds": metric(m, "rush_elapsed_s", "max"),
    "k6_exit": os.environ["K6_EXIT"],
    "issue_p95_ms": metric(m, "issue_latency", "p(95)"),
    "issue_p99_ms": metric(m, "issue_latency", "p(99)"),
    "issue_med_ms": metric(m, "issue_latency", "med"),
    "http_req_failed_rate": metric(m, "http_req_failed", "value"),
    "iterations_per_sec": metric(m, "iterations", "rate"),
    "success": metric(m, "issue_success", "count", 0),
    "soldout": metric(m, "issue_soldout", "count", 0),
    "dup": metric(m, "issue_dup", "count", 0),
    "contention": metric(m, "issue_contention", "count", 0),
    "mismatch": metric(m, "issue_mismatch", "count", 0),
    "pending": metric(m, "issue_pending", "count", 0),
    "timeout": metric(m, "issue_timeout", "count", 0),
    "confirm_p95_ms": metric(m, "confirm_latency", "p(95)"),
    "login_med_ms": metric(m, "login_latency", "med"),
    "bystander_ok_rate": metric(m, "bystander_ok", "value"),
    "bystander_p95_ms": metric(m, "bystander_latency", "p(95)"),
    "hikari_active_max": runtime_max(rows, "hikari_active"),
    "hikari_pending_max": runtime_max(rows, "hikari_pending"),
    "hikari_timeout_max": runtime_max(rows, "hikari_timeout"),
    "tomcat_busy_max": runtime_max(rows, "tomcat_busy"),
    "assert1_issued": issued,
    "assert2_dup_users": dup_users,
    "assert3_remaining": remaining,
    "assert4_waiting": waiting,
    "asserts_pass": "PASS" if asserts_pass else "FAIL",
}

is_new = not LEDGER.exists()
with LEDGER.open("a", newline="") as f:
    w = csv.DictWriter(f, fieldnames=FIELDS)
    if is_new:
        w.writeheader()
    w.writerow(row)

print(f"  {row['strategy']} r{row['round']}: "
      f"p95={row['issue_p95_ms']}ms 발급={issued}/{stock} "
      f"bystander={row['bystander_ok_rate']} 단언={row['asserts_pass']}")
