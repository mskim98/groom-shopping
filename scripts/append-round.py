#!/usr/bin/env python3
"""회차 하나의 결과를 원장(rounds.csv) 한 줄로 추가한다

마지막 종합표를 손으로 옮겨 적지 않으려는 것이다. 라운드가 늘면 회차가 수십 개가 되는데,
그때 사람이 옮기면 어느 칸이 어느 회차의 값인지 어긋나도 알아챌 수단이 없다.
"""
import csv
import json
import re
import os
import pathlib

ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")

LEDGER = pathlib.Path(os.environ["LEDGER"])
SUMMARY = pathlib.Path(os.environ["SUMMARY"])
RUNTIME = pathlib.Path(os.environ["RUNTIME"])

FIELDS = [
    "date", "strategy", "round", "virtual_threads", "stock", "vus", "iterations",
    "load_seconds", "rush_seconds", "k6_exit",
    "issue_p95_ms", "issue_p99_ms", "issue_med_ms",
    "http_req_failed_rate", "iterations_per_sec", "http_reqs_per_sec",
    # 병목을 좁히는 사다리 - 싼 것부터 순서대로
    "db_cpu_pct_max", "db_mem_mb_max", "cache_hit_ratio_pct", "slow_query_max_ms",
    "pg_active_max", "pg_lock_waits_max",
    "success", "soldout", "dup", "contention", "mismatch", "pending", "timeout",
    "confirm_p95_ms", "auth_fail",
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


def parse_dbstats(path):
    """docker stats 스트림에서 CPU 최댓값과 메모리 최댓값을 뽑는다

    형식: '12.34%,256MiB / 7.5GiB'
    """
    cpu_max = mem_max = ""
    p = pathlib.Path(path) if path else None
    if not p or not p.exists():
        return cpu_max, mem_max
    cpus, mems = [], []
    for line in p.read_text(errors="ignore").splitlines():
        # docker stats 스트림은 매 갱신마다 커서 이동 · 지움 문자를 끼워 넣는다
        # 개별 문자열로 지우면 남는 것이 있어 float 변환이 통째로 실패한다 (실측: 27% 를 0 으로 읽음)
        line = ANSI.sub("", line).strip()
        if "%" not in line or "," not in line:
            continue
        cpu_raw, mem_raw = line.split(",", 1)
        try:
            cpus.append(float(cpu_raw.replace("%", "").strip()))
        except ValueError:
            continue
        mem_used = mem_raw.split("/")[0].strip()
        try:
            if mem_used.endswith("GiB"):
                mems.append(float(mem_used[:-3]) * 1024)
            elif mem_used.endswith("MiB"):
                mems.append(float(mem_used[:-3]))
        except ValueError:
            pass
    return (round(max(cpus), 1) if cpus else ""), (round(max(mems), 1) if mems else "")


def cache_hit_ratio(before, after):
    """회차 동안의 적중률. 누적값이라 전후 차이로 계산해야 한다

    누적값을 그대로 쓰면 서버가 오래 떠 있을수록 100%에 가까워져 아무것도 못 가른다
    """
    try:
        hb, rb = (int(x) for x in before.split(","))
        ha, ra = (int(x) for x in after.split(","))
    except (ValueError, AttributeError):
        return ""
    hit, read = ha - hb, ra - rb
    total = hit + read
    return round(100.0 * hit / total, 2) if total > 0 else ""


db_cpu, db_mem = parse_dbstats(os.environ.get("DBSTATS", ""))
cache_hit = cache_hit_ratio(os.environ.get("CACHE_BEFORE", ""), os.environ.get("CACHE_AFTER", ""))

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

# 단언 3 의 기대값은 라운드마다 반대다
# S0 db-lock · S1 lua-then-lock : quantity 는 "남은 수량"이라 확정마다 감산되므로 0
# S2 kafka                      : quantity 는 "불변 발행 수량"이라 아무도 고치지 않으므로 stock 그대로
# 감산을 없앤 것이 S2 의 본체다 - 한쪽 기대값을 고정해 두면 다른 라운드에서
# 결함이 없는데도 항상 실패한다 (실제로 kafka r1 이 전 단언을 통과하고도 FAIL 로 적혔다)
expected_quantity = stock if os.environ["STRATEGY"] == "kafka" else 0

# 단언 4종을 하나로 접어 둔다. 어느 것이 깨졌는지는 개별 칸에 그대로 남아 있다
# 미달(issued < stock)도 실패로 센다 - S1 에서는 그것이 예상 결과이고, 예상 결과도 실패로 기록해야
# "무엇을 지켜내지 못했는가"가 표에 남는다
asserts_pass = (issued == stock and dup_users == 0
                and remaining == expected_quantity and waiting == 0)

row = {
    "date": os.environ["STAMP"],
    "strategy": os.environ["STRATEGY"],
    "round": os.environ["ROUND"],
    # 켜고 끈 회차를 섞으면 결과 해석이 통째로 갈린다
    "virtual_threads": os.environ.get("VIRTUAL_THREADS", "unknown"),
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
    # 1단 - 들어온 트래픽 자체가 감당 밖인가
    "http_reqs_per_sec": metric(m, "http_reqs", "rate"),
    # 2단 - DB 자원이 모자란가
    "db_cpu_pct_max": db_cpu,
    "db_mem_mb_max": db_mem,
    # 3단 - 디스크를 때리고 있는가 · 느린 쿼리가 있는가
    "cache_hit_ratio_pct": cache_hit,
    "slow_query_max_ms": os.environ.get("SLOW_TOP_MS", ""),
    # 5단 - DB 안에서 실제로 돌던 세션과 잠금 대기
    "pg_active_max": runtime_max(rows, "pg_active"),
    "pg_lock_waits_max": runtime_max(rows, "pg_lock_waits"),
    "success": metric(m, "issue_success", "count", 0),
    "soldout": metric(m, "issue_soldout", "count", 0),
    "dup": metric(m, "issue_dup", "count", 0),
    "contention": metric(m, "issue_contention", "count", 0),
    "mismatch": metric(m, "issue_mismatch", "count", 0),
    "pending": metric(m, "issue_pending", "count", 0),
    "timeout": metric(m, "issue_timeout", "count", 0),
    "confirm_p95_ms": metric(m, "confirm_latency", "p(95)"),
    # 0 이 아니면 인증 우회가 안 먹은 것이다. 그 회차는 증거가 아니다
    "auth_fail": metric(m, "auth_fail", "count", 0),
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
if not is_new:
    # 칸을 추가한 뒤 옛 헤더에 이어 붙이면 값이 한 칸씩 밀려 기록된다
    # 밀린 것은 오류를 내지 않아서 표를 읽을 때까지 드러나지 않는다 (실제로 겪었다)
    with LEDGER.open() as f:
        existing = next(csv.reader(f), [])
    if existing != FIELDS:
        archived = LEDGER.with_name(f"{LEDGER.stem}_before_{len(existing)}cols.csv")
        LEDGER.rename(archived)
        print(f"  [원장] 칸 구성이 바뀌었다. 옛 파일을 {archived.name} 로 옮기고 새로 시작한다")
        is_new = True

with LEDGER.open("a", newline="") as f:
    w = csv.DictWriter(f, fieldnames=FIELDS)
    if is_new:
        w.writeheader()
    w.writerow(row)

print(f"  {row['strategy']} r{row['round']}: "
      f"p95={row['issue_p95_ms']}ms 발급={issued}/{stock} "
      f"bystander={row['bystander_ok_rate']} 단언={row['asserts_pass']}"
      f" (quantity={remaining} 기대={expected_quantity})")
print(f"  [사다리] rps={row['http_reqs_per_sec']} DB CPU={db_cpu}% MEM={db_mem}MiB "
      f"캐시적중={cache_hit}% 최장쿼리={row['slow_query_max_ms']}ms "
      f"풀대기={row['hikari_pending_max']} 잠금대기={row['pg_lock_waits_max']}")
