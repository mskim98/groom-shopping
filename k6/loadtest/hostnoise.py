#!/usr/bin/env python3
"""호스트의 사용자 앱이 측정을 방해했는지 `top` 표본 파일로 판정한다 (Ruling R123-18)

세는 것: 브라우저와 그 보조 프로세스, ChatGPT, Codex, Notion, Slack, 압축 도구, IDE, 빌드(gradle · java) 같은 사용자 앱
세지 않는 것: macOS 서비스, Docker VM, k6, Orca, Claude (이름이 사용자 앱 목록에 걸려도 제외 목록이 먼저 거른다)
규칙: 한 앱이 문턱(기본 코어 하나의 30 %)을 넘은 표본이 연속 2개 이상이면 소음이다. `top -l N` 의 첫 표본은 부팅 이후 평균이라 건너뛴다
시작 전 한 표본 검사는 --any 로 첫 표본도 보고 한 번만 넘어도 소음으로 친다

usage: hostnoise.py <top 파일> [문턱] [--any]
출력: 소음 앱의 JSON {이름: {"max": %, "consecutive": 연속 초과 표본 수의 최댓값, "samples_above": 초과 표본 수}} 또는 {}
"""
import json
import re
import sys

# 소음으로 세는 사용자 앱(top 의 COMMAND 열)
USER = re.compile(r"(browser helper|arc\b|chrome|safari|firefox|chatgpt|codex|notion|slack|bsdtar|\btar\b|xcode|gradle|java|idea|code helper|zoom|teams|spotify|figma)", re.I)
# 이름이 위 목록에 걸려도 세지 않는 것 - Orca 번들 안의 chrome_crashpad_handler 는 Chrome 이 아니라 Orca 의 오류 수집기다
EXCLUDED = re.compile(r"(orca|claude|\bk6\b|docker|com\.apple\.|crashpad|vz|qemu|kernel_task|windowserver)", re.I)


def samples_of(path):
    """top 파일을 표본 목록으로 읽는다 - 각 표본은 {앱 이름: 그 표본의 최대 %CPU}. 표본은 `PID` 머리줄에서 시작한다"""
    out, cur = [], None
    with open(path, errors="replace") as f:
        for line in f:
            if line.startswith("PID"):
                cur = {}
                out.append(cur)
            elif cur is not None:
                m = re.match(r"\s*(\d+)\s+([\d.]+)\s+(.*)", line)
                # 1500 % 이상은 top 의 첫 표본에 찍히는 누적 값이라 버린다
                if m and float(m.group(2)) < 1500 and USER.search(m.group(3)) and not EXCLUDED.search(m.group(3)):
                    name = m.group(3).strip()
                    cur[name] = max(cur.get(name, 0.0), float(m.group(2)))
    return out


def sample_count(path, skip_first=True):
    """판정에 쓰이는 표본 수 - 첫 표본을 건너뛰면 그만큼 뺀다"""
    n = len(samples_of(path))
    return n - 1 if skip_first and n > 1 else n


def noisy(path, threshold=30.0, any_one=False, skip_first=True):
    s = samples_of(path)
    if skip_first and len(s) > 1:
        s = s[1:]
    res = {}
    for name in {n for x in s for n in x}:
        run = best = above = 0
        peak = 0.0
        for x in s:
            v = x.get(name, 0.0)
            peak = max(peak, v)
            if v > threshold:
                run += 1
                above += 1
                best = max(best, run)
            else:
                run = 0
        if above and (any_one or best >= 2):
            res[name] = {"max": peak, "consecutive": best, "samples_above": above}
    return res


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        sys.exit(2)
    any_one = "--any" in sys.argv
    print(json.dumps(noisy(args[0], float(args[1]) if len(args) > 1 else 30.0, any_one, skip_first=not any_one)))
