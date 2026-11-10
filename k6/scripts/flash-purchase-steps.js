import http from 'k6/http';
import exec from 'k6/execution';
import {Counter, Trend} from 'k6/metrics';

/**
 * 플래시 상품 구매 흐름 단계 측정 - 한 번 실행에 한 단계(목표 요청률 하나)
 *
 * 한 반복 = 장바구니 담기 → 주문 → 결제 확정 (HTTP 요청 3건). 요청률 = nginx 를 지나는 초당 HTTP 요청 수
 * 주문은 그 반복의 상품 1개만 담는다: 주문 · 확정이 성공으로 끝나지 않으면 항목을 제거하고, 확정 200 뒤에는 다음 반복 시작에 장바구니를 조회해 남은 항목을 비운다(추가 요청은 요약의 extra_requests)
 * 열린 모델(ramping-arrival-rate): 응답이 느려져도 도착률은 그대로라 밀린 만큼 dropped_iterations 가 늘고 VU 가 늘어난다
 *
 * 실행 예(run.sh 가 부른다):
 *   k6 run -e TARGET_RPS=2000 -e MAX_VUS=1000 -e SUMMARY_FILE=/tmp/step.json k6/scripts/flash-purchase-steps.js
 * 환경 변수: TARGET_RPS(필수) · RAMP_S(10) · HOLD_S(30) · BASE_URL(http://localhost/api/v1) · MAX_VUS(TARGET_RPS/2) ·
 *   FLASH_COUNT(20) · SUMMARY_FILE(k6-summary.json) · LT_PASSWORD(loadtest123!)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '0', 10);
const RAMP_S = parseInt(__ENV.RAMP_S || '10', 10);
const HOLD_S = parseInt(__ENV.HOLD_S || '30', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || String(Math.max(Math.ceil(TARGET_RPS / 2), 1)), 10);
const FLASH_COUNT = parseInt(__ENV.FLASH_COUNT || '20', 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'k6-summary.json';
const REQUESTS_PER_ITERATION = 3;
const ENDPOINTS = ['cart_add', 'order', 'confirm'];
const CLASSES = ['2xx', '429', '503', '5xx', 'other4xx', 'fail'];

// 주문이 반복 한 번의 상품 1개만 담게 하려고 보내는 추가 요청(장바구니 항목 제거 · 비우기) - 흐름 3건의 요청률에는 넣지 않고 따로 센다
const extra = {cart_remove: new Counter('extra_cart_remove'), cart_get: new Counter('extra_cart_get'), cart_clear: new Counter('extra_cart_clear')};
// VU 별 상태(각 VU 는 독립 JS 런타임이라 모듈 변수는 VU 로컬): 장바구니가 비어 있음이 확인된 상태인가. setup 이 비웠으므로 처음엔 참이고,
// 결제 확정 200 뒤에는 서버가 비동기로 비우는 일이 밀려 있을 수 있어 거짓이다(부하 때 그 지연이 다음 반복의 주문에 옛 항목을 섞었다)
let cartKnownEmpty = true;

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
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max', 'count'],
    scenarios: {
        // 요청률 / 반복당 요청 수 = 반복률. 시간 단위를 3초로 두면 정수 나눗셈 없이 TARGET_RPS 가 그대로 반복률이 된다
        step: {
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: REQUESTS_PER_ITERATION + 's',
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

// 시드 사용자 seed_<k>@test.com (비밀번호 loadtest123!) 를 VU 수만큼 로그인시키고, 시드가 넣어 둔 장바구니 항목을 비운다
// 장바구니에 남은 항목이 있으면 주문이 그 항목까지 담아 반복이 "상품 1개 주문" 이 아니게 된다 - 이 요청들은 측정 구간 밖이다
export function setup() {
    const tokens = [];
    const batch = 50;
    let extra = 0;
    for (let from = 1; from <= MAX_VUS; from += batch) {
        const reqs = [];
        for (let k = from; k < from + batch && k <= MAX_VUS; k++) {
            reqs.push(['POST', BASE_URL + '/auth/login', JSON.stringify({email: 'seed_' + k + '@test.com', password: PASSWORD}),
                {headers: jsonHeaders(null), tags: {name: 'setup_login'}}]);
        }
        const res = http.batch(reqs);
        for (const r of res) {
            if (r.status !== 200) {
                throw new Error('로그인 실패 status=' + r.status + ' body=' + r.body);
            }
            tokens.push(r.json('accessToken'));
        }
    }
    let cleared = 0;
    for (let from = 0; from < tokens.length; from += batch) {
        const slice = tokens.slice(from, from + batch);
        const carts = http.batch(slice.map((t) => ['GET', BASE_URL + '/cart', null,
            {headers: jsonHeaders(t), tags: {name: 'setup_cart_get'}}]));
        extra += carts.length;
        const removals = [];
        carts.forEach((c, i) => {
            const items = c.status === 200 ? (c.json('items') || []) : [];
            if (items.length > 0) {
                removals.push(['DELETE', BASE_URL + '/cart/remove',
                    JSON.stringify({items: items.map((it) => ({productId: it.productId, quantity: it.quantity}))}),
                    {headers: jsonHeaders(slice[i]), tags: {name: 'setup_cart_remove'}}]);
            }
        });
        if (removals.length > 0) {
            http.batch(removals);
            cleared += removals.length;
            extra += removals.length;
        }
    }
    console.log('setup: logins=' + tokens.length + ' carts_cleared=' + cleared + ' extra_requests=' + (tokens.length + extra));
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
    }
}

function removeItem(headers, productId) {
    const r = http.del(BASE_URL + '/cart/remove', JSON.stringify({items: [{productId, quantity: 1}]}),
        {headers, tags: {name: 'cart_remove'}, responseType: 'none'});
    extra.cart_remove.add(1);
    // 4xx(항목 없음)는 장바구니에 그 항목이 없다는 뜻이다
    return r.status > 0 && r.status < 500;
}

function clearCart(headers) {
    const cart = http.get(BASE_URL + '/cart', {headers, tags: {name: 'cart_get'}});
    extra.cart_get.add(1);
    const items = cart.status === 200 ? (cart.json('items') || []) : [];
    if (items.length === 0) return cart.status === 200;
    const r = http.del(BASE_URL + '/cart/remove',
        JSON.stringify({items: items.map((it) => ({productId: it.productId, quantity: it.quantity}))}),
        {headers, tags: {name: 'cart_clear'}, responseType: 'none'});
    extra.cart_clear.add(1);
    return r.status >= 200 && r.status < 300;
}

export default function (data) {
    const token = data.tokens[(__VU - 1) % data.tokens.length];
    const hold = Date.now() - exec.scenario.startTime >= RAMP_S * 1000;
    const headers = jsonHeaders(token);
    const n = 1 + Math.floor(Math.random() * FLASH_COUNT);
    const productId = 'f1a50000-0000-4000-8000-' + String(n).padStart(12, '0');

    if (!cartKnownEmpty) {
        cartKnownEmpty = clearCart(headers);
    }

    const add = http.post(BASE_URL + '/cart/add', JSON.stringify({productId, quantity: 1}),
        {headers, tags: {name: 'cart_add'}, responseType: 'none'});
    record('cart_add', add, hold);
    if (statusClass(add) !== '2xx') {
        // 담기 응답이 5xx · 시간 초과면 서버에 항목이 들어갔는지 알 수 없다 - 4xx 는 서버가 담지 않은 것이다
        const unknown = add.status >= 500 || add.status === 0;
        if (unknown) cartKnownEmpty = removeItem(headers, productId);
        return;
    }

    // 이 반복의 주문은 방금 담은 상품 1개만 담아야 한다 - 주문이나 확정이 성공으로 끝나지 않으면 항목을 바로 빼서 다음 주문에 섞이지 않게 한다
    const order = http.post(BASE_URL + '/order', JSON.stringify({couponId: null}), {headers, tags: {name: 'order'}});
    record('order', order, hold);
    if (order.status !== 201) {
        cartKnownEmpty = removeItem(headers, productId);
        return;
    }

    const paymentKey = 'lt_pk_' + __VU + '_' + __ITER + '_' + Date.now();
    const confirm = http.post(BASE_URL + '/payment/confirm',
        JSON.stringify({paymentKey, orderId: order.json('orderId'), amount: order.json('totalAmount')}),
        {headers, tags: {name: 'confirm'}, responseType: 'none'});
    record('confirm', confirm, hold);
    // 200 은 서버가 비동기로 장바구니를 비운다 - 끝났는지는 다음 반복 시작에 조회로 확인한다. 202(컨슈머 대기 초과)나 실패는 서버가 비우지 않으므로 직접 뺀다
    cartKnownEmpty = confirm.status === 200 ? false : removeItem(headers, productId);
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
            p95: v['p(95)'] || 0, p99: v['p(99)'] || 0, avg: v.avg || 0, max: v.max || 0,
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
        // 흐름 3건 밖의 추가 요청(전 구간 합계): 항목 제거 · 장바구니 조회 · 장바구니 비우기
        extra_requests: {cart_remove: count(data, 'extra_cart_remove'), cart_get: count(data, 'extra_cart_get'), cart_clear: count(data, 'extra_cart_clear')},
    };
    return {
        [SUMMARY_FILE]: JSON.stringify(summary, null, 2),
        stdout: 'step ' + TARGET_RPS + ' rps: achieved ' + summary.achieved_rps + ' rps, dropped ' + summary.dropped_iterations
            + ', errors ' + (summary.errors.rate * 100).toFixed(2) + '%\n',
    };
}
