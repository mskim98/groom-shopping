#!/usr/bin/env python3
"""run.sh 가 1 초마다 뜬 pg_stat_activity 표본(<out>/pg-activity.tsv)을 요약한다

usage: pg_activity_digest.py [--min-idle-ms 2000] <pg-activity.tsv>
열(탭): 표본 시각 · pid · state · wait_event_type · wait_event · 트랜잭션 나이(ms) · 문장 나이(ms) · pg_blocking_pids · 문장 앞 200 자
첫 줄은 머리줄이고 pid 가 빈 행은 "그 시각에 표본을 떴다"는 표시일 뿐이다
① 잠금 대기 = pg_blocking_pids 가 빈 집합이 아닌 행. 기다리는 문장 · 막는 문장(같은 표본의 그 pid 행) · 막는 세션 상태로 묶는다
   count = 같은 세션이 같은 문장으로 이어서 기다린 것을 한 번으로 센 횟수, samples = 표본 행 수, longest = 기다리는 문장 나이의 최댓값(기다림 시작 뒤 지난 시간의 하한이 아니라 문장이 시작한 뒤 시간이다)
② 트랜잭션 안에서 논 세션 = state 가 `idle in transaction` 이고 트랜잭션 나이가 문턱값(기본 2000 ms) 이상인 행. 마지막으로 낸 문장으로 묶고
   count = 같은 세션의 같은 트랜잭션을 한 번으로 센 횟수, longest = 트랜잭션 나이의 최댓값
같은 세션 · 같은 문장(또는 트랜잭션) 판정 = 표본 시각 - 나이 로 구한 시작 시각이 5 ms 안에서 같은 것
표준 라이브러리만 쓴다
"""
import sys
from datetime import datetime

COLS = ("t", "pid", "state", "wait_type", "wait_event", "xact_age", "query_age", "blocking", "query")
START_TOLERANCE_MS = 5


def _ms(text):
    return int(text) if text.strip().lstrip("-").isdigit() else None


def _time_ms(text):
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%S.%fZ").timestamp() * 1000


def parse(lines):
    """표본 행 목록(딕셔너리)과 서로 다른 표본 시각의 집합 - 머리줄은 시각으로 세지 않는다"""
    rows, times = [], set()
    for i, line in enumerate(lines):
        if not line.strip() or (i == 0 and line.startswith("sample_time")):
            continue
        f = line.split("\t")
        f += [""] * (len(COLS) - len(f))
        row = dict(zip(COLS, f[:len(COLS)]))
        times.add(row["t"])
        if not row["pid"].strip():
            continue
        row["xact_age"], row["query_age"] = _ms(row["xact_age"]), _ms(row["query_age"])
        row["blocking"] = [p for p in row["blocking"].strip("{}").split(",") if p]
        row["t_ms"] = _time_ms(row["t"])
        rows.append(row)
    return rows, times


class Episodes:
    """같은 세션이 같은 시작 시각으로 이어진 구간을 구분한다"""

    def __init__(self):
        self.last = {}  # (pid, 키) -> 시작 시각(ms)

    def is_new(self, pid, key, start_ms):
        prev = self.last.get((pid, key))
        self.last[(pid, key)] = start_ms
        return prev is None or abs(prev - start_ms) > START_TOLERANCE_MS


def digest(lines, min_idle_ms=2000):
    rows, times = parse(lines)
    by_time = {}
    for r in rows:
        by_time.setdefault(r["t"], {})[r["pid"]] = r

    waits, ep = {}, Episodes()
    for r in rows:
        if not r["blocking"] or r["query_age"] is None:
            continue
        for b in r["blocking"]:
            blocker = by_time[r["t"]].get(b)
            blocking_q = blocker["query"] if blocker else "(pid %s not in this sample)" % b
            key = (r["query"], blocking_q, r["wait_type"] + "/" + r["wait_event"], blocker["state"] if blocker else "unknown")
            g = waits.setdefault(key, {"waiting": key[0], "blocking": key[1], "wait_event": key[2].split("/", 1)[1], "wait_event_type": key[2].split("/", 1)[0], "blocker_state": key[3], "count": 0, "samples": 0, "longest_ms": 0})
            g["samples"] += 1
            g["longest_ms"] = max(g["longest_ms"], r["query_age"])
            if ep.is_new(r["pid"], key, r["t_ms"] - r["query_age"]):
                g["count"] += 1

    idle, ep = {}, Episodes()
    for r in rows:
        if r["state"] != "idle in transaction" or r["xact_age"] is None or r["xact_age"] < min_idle_ms:
            continue
        g = idle.setdefault(r["query"], {"query": r["query"], "count": 0, "longest_ms": 0})
        g["longest_ms"] = max(g["longest_ms"], r["xact_age"])
        if ep.is_new(r["pid"], r["query"], r["t_ms"] - r["xact_age"]):
            g["count"] += 1

    return {
        "samples": len(times),
        "min_idle_ms": min_idle_ms,
        "lock_waits": sorted(waits.values(), key=lambda g: (-g["longest_ms"], -g["count"])),
        "idle_in_transaction": sorted(idle.values(), key=lambda g: (-g["longest_ms"], -g["count"])),
    }


def format_report(result):
    out = ["pg_stat_activity samples %d" % result["samples"], "", "== lock waits (waiting statement -> blocking statement)",
           "%6s %8s %11s  %-28s %-20s  %s" % ("count", "samples", "longest_ms", "wait event", "blocker state", "waiting -> blocking")]
    for g in result["lock_waits"]:
        out.append("%6d %8d %11d  %-28s %-20s  %s -> %s" % (g["count"], g["samples"], g["longest_ms"], g["wait_event_type"] + "/" + g["wait_event"], g["blocker_state"], g["waiting"], g["blocking"]))
    out += ["", "== idle in transaction >= %d ms (last statement)" % result["min_idle_ms"], "%6s %11s  %s" % ("count", "longest_ms", "last statement")]
    for g in result["idle_in_transaction"]:
        out.append("%6d %11d  %s" % (g["count"], g["longest_ms"], g["query"]))
    return "\n".join(out) + "\n"


def main(argv):
    min_idle = 2000
    args = argv[1:]
    if args[:1] == ["--min-idle-ms"] and len(args) >= 2:
        min_idle, args = int(args[1]), args[2:]
    if len(args) != 1:
        print(__doc__)
        return 2
    with open(args[0], encoding="utf-8", errors="replace") as f:
        print(format_report(digest(f.read().splitlines(), min_idle)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
