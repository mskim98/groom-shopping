#!/usr/bin/env python3
"""정지 실행들의 원시 폴더에서 정지 구간별 · 상태별 발급 응답 시간을 모아 JSON 한 개로 쓴다 - 요약 파일은 다시 만들지 않는다(읽기만 한다)

usage: pause_window_apply.py <out.json> <name>=<run folder> ...   (run folder 아래에 raw/nginx.log · raw/fault.json)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import derive_r131 as d31  # noqa: E402


def one(folder):
    raw = os.path.join(folder, "raw")
    fault = json.load(open(os.path.join(raw, "fault.json")))
    lo, hi = d31.pause_window_borders(fault)
    out = d31.pause_window_answer_ms(open(os.path.join(raw, "nginx.log"), encoding="utf-8", errors="replace").read(), "/coupon/issue/", lo, hi)
    out["window"] = {"start_utc": lo, "end_utc_exclusive": hi}
    return out


if __name__ == "__main__":
    if len(sys.argv) < 3 or any("=" not in a for a in sys.argv[2:]):
        print(__doc__)
        sys.exit(2)
    result = {}
    for arg in sys.argv[2:]:
        name, folder = arg.split("=", 1)
        result[name] = one(folder)
    json.dump(result, open(sys.argv[1], "w"), indent=1)
    print("wrote %s (%d runs)" % (sys.argv[1], len(result)))
