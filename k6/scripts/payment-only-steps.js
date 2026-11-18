import http from 'k6/http';
import exec from 'k6/execution';
import {Counter, Trend} from 'k6/metrics';

/**
 * 결제 전용 단계 측정 - 한 번 실행에 한 단계(초당 결제 확정 요청 수 하나)
 *
 * 램프와 유지 구간의 요청은 POST /payment/confirm 하나뿐이다. 한 반복 = 확정 1건 = HTTP 요청 1건이라 TARGET_RPS 가 초당 확정 요청 수다
 * 주문은 run.sh 가 이 실행 앞에 payment-only-prepare.js 로 만들어 ORDERS_FILE 에 적어 둔다(측정 구간 밖) - 반복 i 는 파일의 i 번째 주문을 확정한다
 * 열린 모델(ramping-arrival-rate): 응답이 느려져도 도착률은 그대로라 밀린 만큼 dropped_iterations 가 늘고 VU 가 늘어난다
 *
 * 환경 변수: TARGET_RPS(필수) · ORDERS_FILE(필수) · RAMP_S(10) · HOLD_S(30) · BASE_URL(http://localhost/api/v1) · MAX_VUS(TARGET_RPS/2) · SUMMARY_FILE(k6-summary.json) · LT_PASSWORD(loadtest123!)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '0', 10);
const RAMP_S = parseInt(__ENV.RAMP_S || '10', 10);
const HOLD_S = parseInt(__ENV.HOLD_S || '30', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || String(Math.max(Math.ceil(TARGET_RPS / 2), 1)), 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'k6-summary.json';
const ORDERS = JSON.parse(open(__ENV.ORDERS_FILE || 'payonly-orders.json'));
const ENDPOINTS = ['confirm'];
const CLASSES = ['2xx', '429', '503', '5xx', 'other4xx', 'fail'];

// 미리 만든 주문이 모자라 확정하지 못하고 넘긴 반복 수 - 0 이어야 한다
const skipped = new Counter('iterations_skipped');
// 유지 구간에 202(대기 상한을 넘겨 READY 로 답함)로 끝난 확정 수 - 2xx 묶음에서 따로 센다(r129)
const accepted202 = new Counter('st_confirm_202_only');

// 응답 시간은 유지 구간(램프 뒤)에 시작한 요청만 모은다 - 램프 구간의 낮은 부하가 p95 를 낮추지 않게 한다
const latency = {};
const status = {};
const total = {};
for (const ep of ENDPOINTS) {
    latency[ep] = new Trend('lat_' + ep, true);
    total[ep] = new Counter('tot_' + ep);
    status[ep] = {};
    for (const c of CLASSES) {
        status[ep][c] = new Counter('st_' + ep + '_' + c);
    }
}

export const options = {
    setupTimeout: '600s',
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
    scenarios: {
        step: {
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: MAX_VUS,
            maxVUs: MAX_VUS,
            stages: [
                {target: TARGET_RPS, duration: RAMP_S + 's'},
                {target: TARGET_RPS, duration: HOLD_S + 's'},
            ],
        },
    },
};

function jsonHeaders(token) {
    const h = {'Content-Type': 'application/json'};
    if (token) h.Authorization = 'Bearer ' + token;
    return h;
}

// 주문을 가진 시드 사용자(1..ORDERS.users)를 로그인시킨다 - 이 요청들은 측정 구간 밖이다. 로그 줄 형식은 collect.py 가 읽는다
export function setup() {
    const reqs = [];
    for (let k = 1; k <= ORDERS.users; k++) {
        reqs.push(['POST', BASE_URL + '/auth/login', JSON.stringify({email: 'seed_' + k + '@test.com', password: PASSWORD}),
            {headers: jsonHeaders(null), tags: {name: 'setup_login'}}]);
    }
    const tokens = http.batch(reqs).map((r) => {
        if (r.status !== 200) {
            throw new Error('로그인 실패 status=' + r.status + ' body=' + r.body);
        }
        return r.json('accessToken');
    });
    console.log('setup: logins=' + tokens.length + ' carts_cleared=0 extra_requests=' + tokens.length + ' orders=' + ORDERS.orders.length);
    return {tokens};
}

function statusClass(r) {
    const s = r.status;
    if (s >= 200 && s < 300) return '2xx';
    if (s === 429) return '429';
    if (s === 503) return '503';
    if (s >= 500) return '5xx';
    if (s >= 400) return 'other4xx';
    return 'fail';
}

function record(ep, r, hold) {
    total[ep].add(1);
    if (hold) {
        latency[ep].add(r.timings.duration);
        status[ep][statusClass(r)].add(1);
        if (r.status === 202) accepted202.add(1);
    }
}

export default function (data) {
    const hold = Date.now() - exec.scenario.startTime >= RAMP_S * 1000;
    const idx = exec.scenario.iterationInTest;
    if (idx >= ORDERS.orders.length) {
        skipped.add(1);
        return;
    }
    const order = ORDERS.orders[idx];
    const headers = jsonHeaders(data.tokens[order.u - 1]);
    const paymentKey = 'lt_pk_' + __VU + '_' + __ITER + '_' + Date.now();
    const confirm = http.post(BASE_URL + '/payment/confirm',
        JSON.stringify({paymentKey, orderId: order.orderId, amount: order.amount}),
        {headers, tags: {name: 'confirm'}, responseType: 'none'});
    record('confirm', confirm, hold);
}

function count(data, name) {
    const m = data.metrics[name];
    return m && m.values && m.values.count ? m.values.count : 0;
}

export function handleSummary(data) {
    const endpoints = {};
    let reqHold = 0;
    let errHold = 0;
    const statusTotal = {'429': 0, '503': 0, '5xx': 0};
    for (const ep of ENDPOINTS) {
        const v = (data.metrics['lat_' + ep] || {}).values || {};
        const st = {};
        let epHold = 0;
        for (const c of CLASSES) {
            st[c] = count(data, 'st_' + ep + '_' + c);
            epHold += st[c];
            if (c !== '2xx') errHold += st[c];
        }
        reqHold += epHold;
        for (const c of ['429', '503', '5xx']) statusTotal[c] += st[c];
        endpoints[ep] = {
            p50: v['p(50)'] || 0, p95: v['p(95)'] || 0, p99: v['p(99)'] || 0, avg: v.avg || 0, max: v.max || 0,
            count: epHold, count_total: count(data, 'tot_' + ep), status: st,
        };
    }
    const summary = {
        target_rps: TARGET_RPS, ramp_s: RAMP_S, hold_s: HOLD_S, max_vus: MAX_VUS,
        // 유지 구간에 시작한 요청 수 / 유지 시간
        achieved_rps: Math.round((reqHold / HOLD_S) * 10) / 10,
        dropped_iterations: count(data, 'dropped_iterations'),
        iterations: count(data, 'iterations'),
        endpoints,
        errors: {rate: reqHold > 0 ? errHold / reqHold : 1, status: statusTotal, requests_hold: reqHold},
        iterations_skipped: count(data, 'iterations_skipped'),
        accepted_202: count(data, 'st_confirm_202_only'),
        extra_requests: {},
    };
    return {
        [SUMMARY_FILE]: JSON.stringify(summary, null, 2),
        stdout: 'step ' + TARGET_RPS + ' rps: achieved ' + summary.achieved_rps + ' rps, dropped ' + summary.dropped_iterations
            + ', errors ' + (summary.errors.rate * 100).toFixed(2) + '%\n',
    };
}
