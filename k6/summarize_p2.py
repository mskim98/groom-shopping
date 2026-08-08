#!/usr/bin/env python3
"""P2 재측정 산출물을 표로 묶는다. 없는 파일은 건너뛴다."""
import json, os, sys

BASE = '/Users/mskim/Desktop/PJ/groom-shopping/backend/docs/measurements/p2'
ORDER = ['p2_preflight_conflict_vu10',
         'p2_A_nojitter_vu10', 'p2_B_jitter_vu10',
         'p2_A_nojitter_vu20', 'p2_B_jitter_vu20',
         'p2_S1_optimistic_vu20', 'p2_S2_conditional_vu20', 'p2_S3_pessimistic_vu20']

rows = []
for name in ORDER:
    p = os.path.join(BASE, name + '.json')
    if not os.path.exists(p):
        continue
    m = json.load(open(p))['metrics']
    c = lambda k: m.get(k, {}).get('count', 0)
    cl = m['confirm_latency']
    it = c('iterations')
    rows.append(dict(run=name, vus=int(m['vus_max']['value']), iters=it,
                     success=c('confirm_success'), conflict=c('confirm_conflict'),
                     blocked=c('confirm_blocked'), other=c('confirm_other_fail'),
                     success_rate=round(c('confirm_success') / it * 100, 2) if it else 0,
                     avg=round(cl['avg'], 1), p95=round(cl['p(95)'], 1), p99=round(cl['p(99)'], 1)))

json.dump(rows, open(os.path.join(BASE, 'p2_summary.json'), 'w'), indent=2, ensure_ascii=False)

hdr = f"{'run':28s} {'VU':>3s} {'iters':>6s} {'성공':>6s} {'충돌':>5s} {'성공률%':>7s} {'avg':>7s} {'p95':>7s} {'p99':>8s}"
print(hdr); print('-' * len(hdr))
for r in rows:
    print(f"{r['run']:28s} {r['vus']:3d} {r['iters']:6d} {r['success']:6d} {r['conflict']:5d} "
          f"{r['success_rate']:7.2f} {r['avg']:7.1f} {r['p95']:7.1f} {r['p99']:8.1f}")

# 동일 VU 안에서만 A/B 를 비교한다 - 이전 판본이 VU 20 vs VU 10 을 비교해 무효가 됐다
for vu in (10, 20):
    a = next((r for r in rows if r['run'] == f'p2_A_nojitter_vu{vu}'), None)
    b = next((r for r in rows if r['run'] == f'p2_B_jitter_vu{vu}'), None)
    if a and b:
        print(f"\n[VU {vu}] 지터 OFF→ON  성공률 {a['success_rate']:.2f}% → {b['success_rate']:.2f}% "
              f"({b['success_rate']-a['success_rate']:+.2f}%p) · "
              f"avg {a['avg']:.1f} → {b['avg']:.1f}ms ({(b['avg']-a['avg'])/a['avg']*100:+.1f}%) · "
              f"p95 {a['p95']:.1f} → {b['p95']:.1f}ms · 충돌 {a['conflict']} → {b['conflict']}")
