import http from 'k6/http';
import exec from 'k6/execution';
import {Counter, Trend} from 'k6/metrics';

/**
 * 결제 확정 고정 부하(r129) - 미리 만든 주문(payment-only-prepare.js 의 ORDERS_FILE)을 반복마다 하나씩 확정하고 응답을 상태 · 오류 코드별로 센다
 * MODE=arrival(기본): 초당 TARGET_RPS 건을 HOLD_S 초 동안 일정하게(constant-arrival-rate) - 요청 수 = TARGET_RPS x HOLD_S (RAMP_S 는 쓰지 않는다)
 * MODE=vus: VUS 명이 HOLD_S 초 동안 끝나면 바로 다음 주문을 확정한다(constant-vus) - 응답이 느리면 요청 수가 줄어든다
 * 환경 변수: ORDERS_FILE(필수) · MODE · TARGET_RPS · HOLD_S · VUS(200) · MAX_VUS · BASE_URL · SUMMARY_FILE · LT_PASSWORD
 * 한 줄 출력: summary 의 by_status = 상태 코드별 {count, p50, p95}, by_code = 오류 본문의 code(예: PAYMENT_PG_BUSY)별 건수
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const MODE = __ENV.MODE || 'arrival';
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '10', 10);
const HOLD_S = parseInt(__ENV.HOLD_S || '10', 10);
const VUS = parseInt(__ENV.VUS || '200', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || String(Math.max(TARGET_RPS * 10, 50)), 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'k6-summary.json';
const ORDERS = JSON.parse(open(__ENV.ORDERS_FILE || 'payonly-orders.json'));
// 응답에 나올 수 있는 상태 코드와 오류 코드 - k6 지표는 시작 전에 만들어 둬야 해서 목록으로 둔다(그 밖은 x)
const STATUSES = ['200', '201', '202', '400', '409', '422', '429', '500', '502', '503', '504', '0', 'x'];
const CODES = ['PAYMENT_PG_BUSY', 'PAYMENT_PG_TIMEOUT', 'FLASH_ENTRY_LIMITED', 'INSUFFICIENT_STOCK', 'SERVER_ERROR', 'DB_POST_PROCESS_FAILED', 'none', 'other'];

const skipped = new Counter('iterations_skipped');
const lat = {};
const cnt = {};
for (const s of STATUSES) {
    lat[s] = new Trend('lat_' + s, true);
    cnt[s] = new Counter('st_' + s);
}
const codeCnt = {};
for (const c of CODES) codeCnt[c] = new Counter('code_' + c);

export const options = {
    setupTimeout: '600s',
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
    scenarios: {
        confirm: MODE === 'vus'
            ? {executor: 'constant-vus', vus: VUS, duration: HOLD_S + 's', gracefulStop: '90s'}
            : {executor: 'constant-arrival-rate', rate: TARGET_RPS, timeUnit: '1s', duration: HOLD_S + 's', preAllocatedVUs: MAX_VUS, maxVUs: MAX_VUS, gracefulStop: '90s'},
    },
};

export function setup() {
    const reqs = [];
    for (let k = 1; k <= ORDERS.users; k++) {
        reqs.push(['POST', BASE_URL + '/auth/login', JSON.stringify({email: 'seed_' + k + '@test.com', password: PASSWORD}),
            {headers: {'Content-Type': 'application/json'}, tags: {name: 'setup_login'}}]);
    }
    const tokens = http.batch(reqs).map((r) => {
        if (r.status !== 200) throw new Error('로그인 실패 status=' + r.status + ' body=' + r.body);
        return r.json('accessToken');
    });
    console.log('setup: logins=' + tokens.length + ' carts_cleared=0 extra_requests=' + tokens.length + ' orders=' + ORDERS.orders.length);
    return {tokens};
}

export default function (data) {
    const idx = exec.scenario.iterationInTest;
    if (idx >= ORDERS.orders.length) {
        skipped.add(1);
        return;
    }
    const order = ORDERS.orders[idx];
    const headers = {'Content-Type': 'application/json', Authorization: 'Bearer ' + data.tokens[order.u - 1]};
    const paymentKey = 'lt_pk_' + __VU + '_' + __ITER + '_' + Date.now();
    const r = http.post(BASE_URL + '/payment/confirm', JSON.stringify({paymentKey, orderId: order.orderId, amount: order.amount}),
        {headers, tags: {name: 'confirm'}, timeout: '120s'});
    const s = STATUSES.indexOf(String(r.status)) >= 0 ? String(r.status) : 'x';
    cnt[s].add(1);
    lat[s].add(r.timings.duration);
    if (r.status >= 400) {
        const m = /"code"\s*:\s*"([A-Z_]+)"/.exec(String(r.body));
        codeCnt[m && CODES.indexOf(m[1]) >= 0 ? m[1] : m ? 'other' : 'none'].add(1);
    }
}

function count(data, name) {
    const m = data.metrics[name];
    return m && m.values && m.values.count ? m.values.count : 0;
}

export function handleSummary(data) {
    const byStatus = {};
    let sent = 0;
    for (const s of STATUSES) {
        const n = count(data, 'st_' + s);
        sent += n;
        if (n > 0) {
            const v = data.metrics['lat_' + s].values;
            byStatus[s] = {count: n, p50: v['p(50)'], p95: v['p(95)'], p99: v['p(99)'], max: v.max};
        }
    }
    const byCode = {};
    for (const c of CODES) if (count(data, 'code_' + c) > 0) byCode[c] = count(data, 'code_' + c);
    const out = {
        mode: MODE, target_rps: TARGET_RPS, hold_s: HOLD_S, vus: MODE === 'vus' ? VUS : null, max_vus: MAX_VUS,
        sent, orders_available: ORDERS.orders.length, iterations_skipped: count(data, 'iterations_skipped'),
        dropped_iterations: count(data, 'dropped_iterations'), by_status: byStatus, by_code: byCode,
    };
    return {[SUMMARY_FILE]: JSON.stringify(out, null, 2), stdout: 'confirm-fixed ' + MODE + ': sent ' + sent + ' ' + JSON.stringify(byStatus) + '\n'};
}
