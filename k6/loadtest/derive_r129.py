"""r129 측정 요약 키를 원시 파일 내용에서 계산하는 순수 함수 모음 - 스택 없이 돈다(docker · psql · 파일 읽기를 하지 않는다, 입력은 이미 읽은 문자열 · dict)
derive-final.py 의 r129 하위 명령(r129-summary.py)이 부르고, 시험은 test_derive_final.py 의 DeriveR129Test 다"""
import json
import re
from datetime import datetime, timezone

OUTCOMES = ("issued", "sold_out", "duplicate", "other")
# 측정 도구가 DB 에 건 문장(연결 수 표본 · 시계 · 확장 조회) - db_calls 합계에서 뺀다
HARNESS_SQL = re.compile(r"pg_stat_activity|pg_stat_statements|pg_blocking_pids|pg_terminate_backend|pg_database|pg_extension|statement_timestamp", re.I)


def pct(values, p):
    """가장 가까운 순위 방식 - 값이 없으면 None"""
    v = sorted(values)
    return v[max(0, min(len(v) - 1, -(-p * len(v) // 100) - 1))] if v else None


def answer_ms_by_outcome(k6):
    """k6 요약의 answer_ms(결과별 count · p50 · p95 · p99)를 네 결과 모두 채워 돌려준다 - 없는 결과는 count 0 · 값 null"""
    src = k6.get("answer_ms", {})
    out = {}
    for o in OUTCOMES:
        s = src.get(o)
        out[o] = {"count": s["count"], "p50": s["p50"], "p95": s["p95"], "p99": s["p99"]} if s and s.get("count") else {"count": 0, "p50": None, "p95": None, "p99": None}
    return out


def parse_pgss(text):
    """줄 형식 <queryid>\\t<calls>\\t<query> -> [(queryid, calls, query)]"""
    rows = []
    for line in text.splitlines():
        f = line.split("\t", 2)
        if len(f) == 3 and f[1].strip().isdigit():
            rows.append((f[0], int(f[1]), f[2]))
    return rows


def db_calls(rows, requests, setup_rows=None):
    """pg_stat_statements 를 리셋한 뒤 읽은 문장별 호출 수의 합 - 측정 도구 문장은 뺀다. setup_rows(k6 준비 단계 끝 시점의 같은 표)가 있으면 그 뒤의 호출만 따로 센다"""
    own = [r for r in rows if not HARNESS_SQL.search(r[2])]
    total = sum(r[1] for r in own)
    out = {
        "total": total, "per_request": round(total / requests, 4) if requests else None,
        "harness_excluded": sum(r[1] for r in rows if HARNESS_SQL.search(r[2])),
        "coupon_calls": sum(r[1] for r in own if re.search(r"coupon", r[2], re.I)),
        "top": [{"query": r[2][:150], "calls": r[1]} for r in sorted(own, key=lambda r: -r[1])[:5]],
    }
    if setup_rows is not None:
        before = {r[0]: r[1] for r in setup_rows}
        after = sum(max(r[1] - before.get(r[0], 0), 0) for r in own)
        out["total_excluding_setup"] = after
        out["per_request_excluding_setup"] = round(after / requests, 4) if requests else None
    return out


def stock_update_stats(rows):
    """pgss-top 줄(query \\t calls \\t mean_ms \\t total_ms)에서 상품 재고를 낮추는 UPDATE 중 총 시간이 가장 큰 것 - 없으면 None"""
    best = None
    for line in rows:
        f = line.split("\t")
        if len(f) >= 4 and re.search(r"^\s*update\s+product\b.*\bstock\b", f[0], re.I):
            cand = {"query": f[0][:150], "calls": int(f[1]), "mean_ms": float(f[2]), "total_ms": float(f[3])}
            if best is None or cand["total_ms"] > best["total_ms"]:
                best = cand
    return best


def hotrow_path_check(flash_sale, ledger_sale_rows, confirmed, stock_before, stock_after):
    """이 실행이 의도한 재고 경로를 탔는지 - 플래시: SALE 원장 행 == 확정 결제, 일반: 원장 행 0 이고 재고 감소분 == 확정 결제"""
    if flash_sale:
        return ledger_sale_rows == confirmed
    return ledger_sale_rows == 0 and stock_before - stock_after == confirmed


def toss_concurrent_peak(journal):
    """WireMock 요청 일지에서 승인(confirm) 호출 구간 [loggedDate, loggedDate + totalTime) 이 가장 많이 겹친 수 - 끝난 순간에 시작한 호출은 겹치지 않는다"""
    events = []
    for e in journal.get("requests", []):
        if not e["request"]["url"].endswith("/v1/payments/confirm") or not e.get("timing"):
            continue
        start = e["request"]["loggedDate"]
        events += [(start, 1), (start + e["timing"]["totalTime"], -1)]
    cur = peak = 0
    for _, d in sorted(events):  # 같은 시각이면 -1(끝)이 먼저
        cur += d
        peak = max(peak, cur)
    return peak


def ratelimit_counts(status_counts):
    """k6 가 센 상태 코드별 요청 수 -> 통과(2xx) · 429 · 그 밖. 키는 문자열 상태 코드(0 = 연결 실패)"""
    c = {"sent": 0, "passed": 0, "rejected_429": 0, "other": 0}
    for code, n in status_counts.items():
        c["sent"] += n
        if code == "429":
            c["rejected_429"] += n
        elif code.startswith("2"):
            c["passed"] += n
        else:
            c["other"] += n
    return c


def parse_limit(text):
    """nginx 설정 줄에서 limit_req_zone 의 rate 와 limit_req 의 burst · nodelay"""
    rate = re.search(r"limit_req_zone\b.*\brate=(\S+?);", text)
    lim = re.search(r"limit_req\s+zone=\S+(.*);", text)
    burst = re.search(r"burst=(\d+)", lim.group(1)) if lim else None
    return {"rate": rate.group(1), "burst": int(burst.group(1)), "nodelay": "nodelay" in lim.group(1)}


def expected_pass(rate, burst, offered, duration_s):
    """균일한 도착률 offered(건/초)로 duration_s 초 보낼 때 nginx limit_req(rate 건/초, burst, nodelay)가 통과시킬 건수 - 넘침 계수 excess 를 nginx 와 같은 식으로 갱신한다
    거절된 요청은 excess · 마지막 시각을 바꾸지 않는다"""
    excess, last, passed = 0.0, None, 0
    for i in range(int(offered * duration_s)):
        t = i / offered
        if last is None:
            passed += 1
            last = t
            continue
        e = max(excess - rate * (t - last) + 1, 0.0)
        if e <= burst:
            excess, last, passed = e, t, passed + 1
    return passed


def side_effect_delta(before, after):
    """재발행 전 · 후 부수효과 건수 -> 항목별 증가분과 중복 여부(증가가 하나라도 있으면 True). 항목 이름이 다르면 비교할 수 없다"""
    if set(before) != set(after):
        raise ValueError("side effect keys differ: %s vs %s" % (sorted(before), sorted(after)))
    delta = {k: after[k] - before[k] for k in before}
    return {"delta": delta, "duplicated": any(v != 0 for v in delta.values())}


def offset_sum(describe_text, topic):
    """kafka-consumer-groups --describe 출력에서 토픽의 파티션별 CURRENT-OFFSET 합"""
    total = 0
    for line in describe_text.splitlines():
        f = line.split()
        if len(f) >= 4 and f[1] == topic and f[3].isdigit():
            total += int(f[3])
    return total


def consumed_after_republish(before_text, after_text, topic="payment-events"):
    return offset_sum(after_text, topic) - offset_sum(before_text, topic)


def log_marker_counts(text, markers):
    return {m: text.count(m) for m in markers}


def compensation(journal):
    """WireMock 일지에서 승인 · 취소 -> 승인 수 · 성공한 취소 수(결제 키별 한 번) · 취소 없는 승인 수 · 승인부터 첫 성공 취소까지의 시간"""
    approved, canceled, calls = {}, {}, 0
    approved_keys = {json.loads(e["request"]["body"])["paymentKey"] for e in journal.get("requests", [])
                     if e["request"]["url"].endswith("/v1/payments/confirm") and e["response"]["status"] == 200}
    foreign = 0
    for e in journal.get("requests", []):
        url, at = e["request"]["url"], e["request"]["loggedDate"]
        if url.endswith("/v1/payments/confirm") and e["response"]["status"] == 200:
            key = json.loads(e["request"]["body"])["paymentKey"]
            approved[key] = min(at, approved.get(key, at))
        m = re.search(r"/v1/payments/([^/]+)/cancel$", url)
        if m:
            calls += 1
            foreign += m.group(1) not in approved_keys
            if e["response"]["status"] == 200:
                canceled[m.group(1)] = min(at, canceled.get(m.group(1), at))
    # 시드에 남은 보상 행이 부팅 뒤에 부르는 취소처럼, 이 실행이 승인하지 않은 결제 키의 취소는 세지 않는다
    mine = {k: v for k, v in canceled.items() if k in approved}
    gaps = [mine[k] - approved[k] for k in mine]
    return {"toss_approved": len(approved), "toss_canceled": len(mine), "toss_cancel_calls": calls - foreign, "foreign_cancel_calls": foreign,
            "money_without_order": len(approved) - len(mine),
            "cancel_after_approval_ms": {"p50": pct(gaps, 50), "p95": pct(gaps, 95), "count": len(gaps)}}


def hikari_peaks(prom_text):
    """prom.tsv(<시각>\\t<백엔드>\\t<지표줄>)에서 Hikari 사용 중 · 대기 중 연결의 최댓값과 풀 크기"""
    peak = {"hikaricp_connections_active": 0, "hikaricp_connections_pending": 0, "hikaricp_connections_max": 0}
    for line in prom_text.splitlines():
        f = line.split("\t", 2)
        m = re.match(r"(hikaricp_connections_(?:active|pending|max))\{[^}]*\}\s+([-\d.eE+]+)$", f[2]) if len(f) == 3 else None
        if m:
            peak[m.group(1)] = max(peak[m.group(1)], int(float(m.group(2))))
    return {"active_peak": peak["hikaricp_connections_active"], "pending_peak": peak["hikaricp_connections_pending"], "max": peak["hikaricp_connections_max"]}


def lock_wait_samples(pg_tsv):
    """pg-activity.tsv(1 초 표본)에서 표본 시각마다 wait_event_type = Lock 인 세션 수 -> 최댓값 · 평균(활동이 없던 표본 시각은 0 으로 센다)"""
    per = {}
    for i, line in enumerate(pg_tsv.splitlines()):
        f = line.split("\t")
        if not line.strip() or (i == 0 and f[0] == "sample_time"):
            continue
        per[f[0]] = per.get(f[0], 0) + (1 if len(f) > 3 and f[3] == "Lock" else 0)
    v = list(per.values())
    return {"peak": max(v) if v else None, "mean": round(sum(v) / len(v), 3) if v else None, "samples": len(v)}


def connection_peak(pg_tsv, start_iso, end_iso):
    """pg-activity.tsv(1 초 표본)에서 [start, end) 안의 표본 시각마다 pid 가 있는 행 수 = 그 시각에 문장을 실행 중이거나 트랜잭션을 연 세션 수(표본 문장 자신의 연결 · 유휴 연결은 세지 않는다) -> 최댓값 · 표본 수"""
    lo, hi = _utc(start_iso), _utc(end_iso)
    per = {}
    for i, line in enumerate(pg_tsv.splitlines()):
        f = line.split("\t")
        if not line.strip() or (i == 0 and f[0] == "sample_time"):
            continue
        if lo <= _utc(f[0]) < hi:
            per[f[0]] = per.get(f[0], 0) + (1 if len(f) > 1 and f[1].strip() else 0)
    return {"peak": max(per.values()) if per else None, "samples": len(per)}


def hold_window(stage_start_text, stage_end_text):
    """확정 구간 타이머 표본 두 파일(유지 구간의 시작 · 끝에 읽는다)의 첫 줄 시각 -> (시작, 끝). 한쪽이 비면 None"""
    first = lambda t: t.split("\t", 1)[0].strip() if t.strip() else None
    a, b = first(stage_start_text), first(stage_end_text)
    return (a, b) if a and b else None


def host_idle(top_text):
    """top 표본 파일의 'CPU usage' 줄에서 idle % - 첫 표본은 부팅 뒤 평균이라 건너뛴다. 표본이 하나뿐이면 그 표본을 쓴다"""
    idle = [float(x) for x in re.findall(r"CPU usage:.*?([\d.]+)% idle", top_text)]
    use = idle[1:] if len(idle) > 1 else idle
    if not use:
        return None
    s = sorted(use)
    mid = len(s) // 2
    return {"min": s[0], "median": s[mid] if len(s) % 2 else (s[mid - 1] + s[mid]) / 2, "samples": len(use)}


def _utc(text):
    """2026-11-18T05:00:04.000000000Z · 2026-11-18T14:00:06+09:00 -> epoch 초 (소수 9 자리는 6 자리로 자른다)"""
    t = re.sub(r"(\.\d{6})\d+", r"\1", text.replace("Z", "+00:00"))
    d = datetime.fromisoformat(t)
    return (d if d.tzinfo else d.replace(tzinfo=timezone.utc)).timestamp()


def breaker_transitions(log_text, name, t0_iso):
    """docker logs --timestamps 의 [CB_STATE_CHANGE] <name>: A -> B 줄 -> 기준 시각(t0_iso)에서 몇 초 뒤인지"""
    t0 = _utc(t0_iso)
    out = []
    for line in log_text.splitlines():
        m = re.match(r"(\S+) .*\[CB_STATE_CHANGE\] %s: (\w+) -> (\w+)" % re.escape(name), line)
        if m:
            out.append({"from": m.group(2), "to": m.group(3), "at_s": round(_utc(m.group(1)) - t0, 3)})
    return out


def answers_in_window(nginx_log, uri_part, start_iso, end_iso):
    """nginx 로그(<시각> <메서드> <uri> <상태> …) 중 uri 에 uri_part 가 있고 응답이 끝난 시각이 [start, end) 인 요청의 상태별 수"""
    s, e = _utc(start_iso), _utc(end_iso)
    out = {}
    for line in nginx_log.splitlines():
        f = line.split()
        if len(f) >= 4 and uri_part in f[2] and s <= _utc(f[0]) < e:
            out[f[3]] = out.get(f[3], 0) + 1
    return out
