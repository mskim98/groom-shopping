import http from 'k6/http';
import {Counter} from 'k6/metrics';

/**
 * 속도 제한 측정(r129) - 읽기 경로 하나(GET READ_PATH)를 초당 RATE 건 일정하게 DURATION_S 초 보내고 응답 상태를 센다. 로그인 · 토큰 없이 부르는 공개 경로다
 * 환경 변수: BASE_URL · READ_PATH(/product?size=1) · RATE(100) · DURATION_S(20) · SUMMARY_FILE
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const READ_PATH = __ENV.READ_PATH || '/product?size=1';
const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION_S = parseInt(__ENV.DURATION_S || '20', 10);
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'k6-summary.json';
const STATUSES = ['200', '204', '429', '500', '502', '503', '504', '0'];
const cnt = {};
for (const s of STATUSES) cnt[s] = new Counter('rl_' + s);
const other = new Counter('rl_x');

export const options = {
    summaryTrendStats: ['avg', 'p(95)', 'count'],
    scenarios: {read: {executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION_S + 's', preAllocatedVUs: 100, maxVUs: 400}},
};

export default function () {
    const r = http.get(BASE_URL + READ_PATH, {responseType: 'none', tags: {name: 'read'}});
    const s = String(r.status);
    (cnt[s] || other).add(1);
}

export function handleSummary(data) {
    const c = (n) => (data.metrics[n] && data.metrics[n].values ? data.metrics[n].values.count : 0);
    const status_counts = {};
    for (const s of STATUSES) if (c('rl_' + s) > 0) status_counts[s] = c('rl_' + s);
    if (c('rl_x') > 0) status_counts.x = c('rl_x');
    const out = {rate: RATE, duration_s: DURATION_S, path: READ_PATH, iterations: c('iterations'), dropped_iterations: c('dropped_iterations'), status_counts};
    return {[SUMMARY_FILE]: JSON.stringify(out, null, 2)};
}
