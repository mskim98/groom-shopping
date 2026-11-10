import http from 'k6/http';
import {Trend, Counter} from 'k6/metrics';
import {SharedArray} from 'k6/data';

/**
 * 상품 상세 조회 최종 측정 - 정해진 상품 집합(PRODUCT_IDS_FILE 의 한 줄 한 id)을 균등하게 골라 GET /product/{id} 를 일정한 도착률로 보낸다 (인증 없음)
 * 환경 변수: PRODUCT_IDS_FILE(필수, 절대 경로) · RATE(2000, 초당 요청) · RAMP_S(5) · HOLD_S(30) · MAX_VUS(1000) · BASE_URL · SUMMARY_FILE
 * 응답 시간은 유지 구간에 시작한 요청만 모은다(앞 램프의 낮은 부하가 p95 를 낮추지 않게)
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const RATE = parseInt(__ENV.RATE || '2000', 10);
const RAMP_S = parseInt(__ENV.RAMP_S || '5', 10);
const HOLD_S = parseInt(__ENV.HOLD_S || '30', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '1000', 10);
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'detail-summary.json';
const START_MS = Date.now();

const ids = new SharedArray('ids', () => open(__ENV.PRODUCT_IDS_FILE).split('\n').map((s) => s.trim()).filter((s) => s.length > 0));
const lat = new Trend('detail_ms', true);
const ok = new Counter('detail_ok');
const bad = new Counter('detail_bad');

export const options = {
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max', 'count'],
    scenarios: {
        detail: {
            executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: MAX_VUS, maxVUs: MAX_VUS,
            stages: [{target: RATE, duration: RAMP_S + 's'}, {target: RATE, duration: HOLD_S + 's'}],
        },
    },
};

export default function () {
    const started = Date.now();
    const res = http.get(BASE_URL + '/product/' + ids[Math.floor(Math.random() * ids.length)], {tags: {name: 'product_detail'}});
    if (started - START_MS < RAMP_S * 1000) return;
    lat.add(res.timings.duration);
    if (res.status === 200) ok.add(1); else bad.add(1);
}

export function handleSummary(data) {
    const m = data.metrics;
    const out = {
        rate: RATE, hold_s: HOLD_S, products: ids.length,
        requests: m.detail_ms.values.count,
        ok: m.detail_ok ? m.detail_ok.values.count : 0,
        bad: m.detail_bad ? m.detail_bad.values.count : 0,
        achieved_rps: m.detail_ms.values.count / HOLD_S,
        p95_ms: m.detail_ms.values['p(95)'], p99_ms: m.detail_ms.values['p(99)'], avg_ms: m.detail_ms.values.avg,
        dropped_iterations: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
        max_vus: m.vus_max ? m.vus_max.values.max : null,
    };
    return {[SUMMARY_FILE]: JSON.stringify(out, null, 2)};
}
