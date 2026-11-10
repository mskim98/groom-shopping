#!/usr/bin/env python3
"""백엔드 로그의 Hikari 누수 감지 보고(connection leak detection)를 경로별로 묶어 건수와 점유 시간을 찍는다

usage: leak_digest.py [--threshold-ms 2000] <backend.log>...
보고 = `Connection leak detection triggered for <연결> on thread <스레드>, stack trace follows` 와 이어지는 스택
반환 = 같은 연결 · 같은 스레드의 `Previously reported leaked connection … (unleaked)` 줄
점유 시간 = 반환 줄 시각 - 보고 줄 시각 + 문턱값(보고는 연결을 빌린 지 문턱값이 지난 뒤에 찍힌다). 반환 줄이 없으면 돌아오지 않은 것으로 따로 센다
묶음 두 가지: ① 스택에서 위쪽(안쪽)부터 처음 만나는 groom.backend 프레임 ② 기다림 프레임(JDBC 소켓 읽기 · Thread.sleep · LockSupport.park · HTTP 클라이언트 읽기)
이 있으면 그 종류 + 그 아래쪽에서 가장 가까운 groom.backend 프레임, 없으면 `(no waiting frame)` + 가장 바깥쪽 groom.backend 프레임(요청의 진입점)
한계: Hikari 가 찍는 스택은 연결을 빌린 시점의 스택이라 빌린 뒤 어디서 기다렸는지는 담기지 않는다 - 기다림 프레임은 스택에 있을 때만 잡힌다
표준 라이브러리만 쓴다
"""
import re
import sys
from datetime import datetime

TS = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ")
LEAK = re.compile(r"Connection leak detection triggered for (\S+) on thread (.+?), stack trace follows")
UNLEAKED = re.compile(r"Previously reported leaked connection (\S+) on thread (.+?) was returned to the pool \(unleaked\)")
FRAME = re.compile(r"^\s+at (.+)$")
APP_PREFIX = "groom.backend."
PROXY = re.compile(r"\$\$SpringCGLIB\$\$\d+")
# 요청 · 메시지 · 작업의 진입점이 되는 클래스 이름
ENTRY = re.compile(r"(Controller|Consumer|Listener|Job|Scheduler|Runner|Publisher)[.$]")

# 위에서부터 처음 맞는 규칙이 이긴다
WAITING = (
    ("JDBC socket read", re.compile(r"^(java\.net\.SocketInputStream\.socketRead|sun\.nio\.ch\.SocketDispatcher\.read|sun\.nio\.ch\.NioSocketImpl\.(read|park)|org\.postgresql\.core\.VisibleBufferedInputStream\.)")),
    ("Thread.sleep", re.compile(r"^java\.lang\.Thread\.sleep")),
    ("LockSupport.park", re.compile(r"^(java\.util\.concurrent\.locks\.LockSupport\.park|jdk\.internal\.misc\.Unsafe\.park)")),
    ("HTTP client read", re.compile(r"^(org\.apache\.hc\.|org\.apache\.http\.|java\.net\.http\.|okhttp3\.|org\.springframework\.web\.client\.|org\.springframework\.http\.client\.)")),
)


def waiting_kind(frames):
    """스택 프레임 목록(위쪽 먼저)에서 첫 기다림 프레임의 종류, 없으면 None"""
    for fr in frames:
        for kind, rx in WAITING:
            if rx.match(fr.removeprefix("java.base/")):
                return kind
    return None


def _waiting_index(frames):
    for i, fr in enumerate(frames):
        if waiting_kind([fr]):
            return i
    return None


def _first_app(frames, start=0):
    """start 부터 처음 만나는 groom.backend 프레임. 스프링 프록시 프레임(`X$$SpringCGLIB$$0.m(<generated>)`)은 줄 번호가 없어
    프록시가 감싼 메서드 이름을 앞에 적고 그 다음 실제 프레임(호출한 쪽)을 쓴다"""
    proxied = None
    for f in frames[start:]:
        if not f.startswith(APP_PREFIX):
            continue
        if PROXY.search(f):
            proxied = proxied or PROXY.sub("", f.split("(")[0])
            continue
        return (proxied + " <- " if proxied else "") + f
    return proxied


def _ts(line):
    m = TS.match(line)
    return datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def parse(lines):
    """(보고 목록, 반환 목록) - 보고 {conn, thread, at, frames}, 반환 {conn, thread, at}"""
    reports, returns, cur = [], [], None
    for line in lines:
        ts = _ts(line)
        if ts is not None:
            cur = None
            m = LEAK.search(line)
            if m:
                cur = {"conn": m.group(1), "thread": m.group(2), "at": ts, "frames": []}
                reports.append(cur)
                continue
            m = UNLEAKED.search(line)
            if m:
                returns.append({"conn": m.group(1), "thread": m.group(2), "at": ts})
            continue
        if cur is not None:
            m = FRAME.match(line)
            if m:
                cur["frames"].append(m.group(1).strip())
    return reports, returns


def _group_key_waiting(frames):
    i = _waiting_index(frames)
    if i is None:
        app = [f for f in frames if f.startswith(APP_PREFIX) and not PROXY.search(f)]
        outer = next((f for f in reversed(app) if ENTRY.search(f)), app[-1] if app else None)
        return "(no waiting frame) @ " + (outer or "(no groom.backend frame)")
    return "%s @ %s" % (waiting_kind([frames[i]]), _first_app(frames, i) or "(no groom.backend frame)")


def digest(lines, threshold_ms=2000):
    reports, returns = parse(lines)
    pending = list(returns)
    for r in reports:
        # 같은 연결 · 같은 스레드의 반환 중 보고 뒤에 처음 오는 것 하나를 짝으로 쓴다(연결 번호는 풀 안에서 다시 쓰인다)
        match = next((u for u in pending if u["conn"] == r["conn"] and u["thread"] == r["thread"] and u["at"] >= r["at"]), None)
        if match:
            pending.remove(match)
            r["held_ms"] = int((match["at"] - r["at"]).total_seconds() * 1000) + threshold_ms
        else:
            r["held_ms"] = None

    def group(keyfn):
        groups = {}
        for r in reports:
            g = groups.setdefault(keyfn(r["frames"]), {"key": keyfn(r["frames"]), "count": 0, "returned": 0, "never_returned": 0, "held_ms": [], "threads": set()})
            g["count"] += 1
            g["threads"].add(re.sub(r"\d+$", "N", r["thread"]))
            if r["held_ms"] is None:
                g["never_returned"] += 1
            else:
                g["returned"] += 1
                g["held_ms"].append(r["held_ms"])
        out = sorted(groups.values(), key=lambda g: (-(max(g["held_ms"]) if g["held_ms"] else 0), -g["count"]))
        for g in out:
            g["longest_ms"] = max(g["held_ms"]) if g["held_ms"] else None
            g["threads"] = sorted(g["threads"])
        return out

    never = sum(1 for r in reports if r["held_ms"] is None)
    return {
        "threshold_ms": threshold_ms,
        "totals": {"reports": len(reports), "returned": len(reports) - never, "never_returned": never},
        "by_first_app_frame": group(lambda fr: _first_app(fr) or "(no groom.backend frame)"),
        "by_waiting_frame": group(_group_key_waiting),
    }


def format_report(result):
    t = result["totals"]
    out = ["Hikari leak detection (threshold %d ms): reports %d · returned %d · never returned %d" % (result["threshold_ms"], t["reports"], t["returned"], t["never_returned"]), ""]
    for title, rows in (("by first groom.backend frame", result["by_first_app_frame"]), ("by waiting frame", result["by_waiting_frame"])):
        out.append("== " + title)
        out.append("%6s %9s %6s %11s  %s" % ("count", "returned", "never", "longest_ms", "key (threads)"))
        for g in rows:
            out.append("%6d %9d %6d %11s  %s (%s)" % (g["count"], g["returned"], g["never_returned"], g["longest_ms"] if g["longest_ms"] is not None else "-", g["key"], ", ".join(g["threads"])))
        out.append("")
    return "\n".join(out)


def main(argv):
    threshold = 2000
    args = argv[1:]
    if args[:1] == ["--threshold-ms"] and len(args) >= 2:
        threshold, args = int(args[1]), args[2:]
    if not args:
        print(__doc__)
        return 2
    lines = []
    for p in args:
        with open(p, encoding="utf-8", errors="replace") as f:
            lines.extend(f.read().splitlines())
    print(format_report(digest(lines, threshold)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
