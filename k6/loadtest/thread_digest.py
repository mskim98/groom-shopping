#!/usr/bin/env python3
"""백엔드 JVM 스레드 덤프(SIGQUIT 또는 jcmd Thread.print 의 `Full thread dump` 블록)에서 트랜잭션 안의 요청 스레드가 어디서 기다리는지 센다

usage: thread_digest.py <threads-backend*.txt>...
입력 = 덤프가 여러 개 이어진 파일(컨테이너 로그에서 잘라낸 것이면 사이사이 로그 줄이 섞여도 된다). 파일마다 `Full thread dump` 줄로 덤프를 나눈다
대상 = 이름이 `http-nio-` 로 시작하고 스택에 `TransactionInterceptor` 나 `TransactionAspectSupport` 프레임이 있는 스레드(= 스프링 트랜잭션 안에서 요청을 처리하는 스레드)
기다림 종류 = 가장 안쪽 프레임부터 첫 `groom.backend` · 트랜잭션 프레임 앞까지(= 라이브러리 · JDK 구간)를 위에서부터 처음 맞는 규칙으로 가른다
Hikari pool wait(커넥션 대기 - 이 스레드는 아직 커넥션이 없다) · monitor wait · logging · JDBC socket read · LockSupport.park · application frame(구간이 비어 있음) · other
묶음 두 가지: 스레드 상태(첫 낱말) · `종류 | 가장 안쪽 프레임 @ 처음 만나는 groom.backend 프레임`
한계: 덤프 한 장은 한 순간이라 기다림이 얼마나 이어졌는지는 모른다 - 같은 스레드가 연속한 덤프에서 같은 곳에 있는지는 덤프 간 비교로만 본다
표준 라이브러리만 쓴다
"""
import re
import sys
from collections import Counter

DUMP_START = re.compile(r"Full thread dump")
HEADER = re.compile(r'^"(.+?)" ')
STATE = re.compile(r"^\s+java\.lang\.Thread\.State: (\w+)")
FRAME = re.compile(r"^\s+at (.+)$")
APP_PREFIX = "groom.backend."
TX = re.compile(r"TransactionInterceptor|TransactionAspectSupport")

# 위에서부터 처음 맞는 규칙이 이긴다. 대상은 라이브러리 · JDK 구간의 프레임 전부
KINDS = (
    ("Hikari pool wait", re.compile(r"com\.zaxxer\.hikari\.(pool\.HikariPool\.getConnection|util\.ConcurrentBag\.borrow)")),
    ("logging", re.compile(r"ch\.qos\.logback\.|org\.slf4j\.|org\.apache\.logging\.log4j\.")),
    ("JDBC socket read", re.compile(r"(SocketDispatcher\.read|NioSocketImpl\.(read|park)|SocketInputStream\.socketRead|VisibleBufferedInputStream\.)")),
    ("LockSupport.park", re.compile(r"(LockSupport\.park|Unsafe\.park)")),
)


def parse(lines):
    """덤프 목록. 덤프 = 스레드 목록, 스레드 = {name, state, frames(안쪽 먼저)}"""
    dumps, thread = [], None
    for line in lines:
        if DUMP_START.search(line):
            dumps.append([])
            thread = None
            continue
        if not dumps:
            continue
        m = HEADER.match(line)
        if m:
            thread = {"name": m.group(1), "state": None, "frames": []}
            dumps[-1].append(thread)
            continue
        if thread is None:
            continue
        m = STATE.match(line)
        if m:
            thread["state"] = m.group(1)
            continue
        m = FRAME.match(line)
        if m:
            thread["frames"].append(m.group(1))
        elif not line.strip():
            thread = None
    return dumps


def in_transaction(t):
    return t["name"].startswith("http-nio-") and any(TX.search(f) for f in t["frames"])


def _inner_span(frames):
    """가장 안쪽부터 첫 groom.backend · 트랜잭션 프레임 앞까지"""
    for i, f in enumerate(frames):
        if f.startswith(APP_PREFIX) or TX.search(f):
            return frames[:i]
    return frames


def kind_of(t):
    span = _inner_span(t["frames"])
    if t["state"] == "BLOCKED" or (span and span[0].removeprefix("java.base/").startswith("java.lang.Object.wait")):
        return "monitor wait"
    if not span:
        return "application frame"
    for kind, rx in KINDS:
        if any(rx.search(f) for f in span):
            return kind
    return "other"


def first_app_frame(frames):
    return next((f for f in frames if f.startswith(APP_PREFIX)), "(no groom.backend frame)")


def digest(lines):
    per_dump, by_state, by_kind, by_frame = [], Counter(), Counter(), Counter()
    for dump in parse(lines):
        kinds = Counter()
        threads = [t for t in dump if in_transaction(t)]
        for t in threads:
            kind = kind_of(t)
            kinds[kind] += 1
            by_state[(t["state"] or "UNKNOWN")] += 1
            by_frame["%s | %s @ %s" % (kind, t["frames"][0] if t["frames"] else "(no frame)", first_app_frame(t["frames"]))] += 1
        by_kind.update(kinds)
        per_dump.append({"tx_threads": len(threads), "by_kind": dict(kinds)})
    return {
        "dumps": len(per_dump),
        "tx_threads_total": sum(d["tx_threads"] for d in per_dump),
        "per_dump": per_dump,
        "by_state": dict(by_state),
        "by_kind": dict(by_kind),
        "by_innermost_frame": [{"key": k, "count": n} for k, n in by_frame.most_common()],
    }


def format_report(r):
    total = r["tx_threads_total"] or 1
    out = ["Thread dumps: dumps %d · request threads inside a transaction, summed over dumps %d (mean %.1f per dump)" % (r["dumps"], r["tx_threads_total"], r["tx_threads_total"] / max(r["dumps"], 1)), ""]
    out.append("== by state")
    out += ["%6d %5.1f%%  %s" % (n, 100.0 * n / total, k) for k, n in sorted(r["by_state"].items(), key=lambda kv: -kv[1])]
    out += ["", "== by wait kind (innermost library/JDK frames below the first groom.backend or transaction frame)"]
    out += ["%6d %5.1f%%  %s" % (n, 100.0 * n / total, k) for k, n in sorted(r["by_kind"].items(), key=lambda kv: -kv[1])]
    out += ["", "== by innermost frame (kind | innermost frame @ first groom.backend frame)"]
    out += ["%6d  %s" % (g["count"], g["key"]) for g in r["by_innermost_frame"]]
    out += ["", "== per dump (index: threads · kinds)"]
    for i, d in enumerate(r["per_dump"], 1):
        out.append("%4d: %3d  %s" % (i, d["tx_threads"], ", ".join("%s %d" % kv for kv in sorted(d["by_kind"].items(), key=lambda kv: -kv[1]))))
    return "\n".join(out) + "\n"


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    lines = []
    for p in argv[1:]:
        with open(p, encoding="utf-8", errors="replace") as f:
            lines.extend(f.read().splitlines())
    print(format_report(digest(lines)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
