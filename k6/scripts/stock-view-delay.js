import http from 'k6/http';
import exec from 'k6/execution';
import {sleep} from 'k6';

/**
 * 재고 노출 지연(r131) - 결제 하나를 확정한 뒤 상품 상세가 낮아진 남은 수량을 처음 보이기까지 걸린 시간을 한 건씩 잰다
 * 반복 하나 = ① 상품 상세를 읽어 남은 수량을 기억한다(캐시에 옛 값을 채운다) ② 주문 하나를 확정한다 ③ 확정 응답이 온 시각부터 POLL_MS 마다 상품 상세를 읽어
 * 남은 수량이 ①보다 낮아진 첫 응답의 도착 시각을 적는다 - LIMIT_MS 안에 낮아지지 않으면 seen_ms 를 null 로 두고 not_seen 으로 센다
 * 가상 사용자 1명이 PAYMENTS 건을 차례로 확정한다(한 번에 결제 하나라 다른 쓰기가 수량을 바꾸지 않는다). 상품 상세는 인증이 필요 없어 토큰 없이 읽는다
 * 주문은 run.sh 가 payment-only-prepare.js 로 미리 만들어 ORDERS_FILE 에 적어 둔다(반복 i 는 i 번째 주문)
 *
 * 환경 변수: ORDERS_FILE(필수) · PRODUCT_ID(필수) · PAYMENTS(100) · POLL_MS(50) · LIMIT_MS(15000) · BASE_URL · SUMMARY_FILE · LT_PASSWORD
 * 쌍은 반복마다 한 줄로 로그에 적는다(handleSummary 는 반복의 변수를 보지 못한다): PAIR i=<번호> status=<확정 상태> before=<읽은 수량> end=<확정 응답 epoch ms> seen=<처음 낮게 보인 epoch ms | null> seen_stock=<그때 수량 | null> polls=<읽은 횟수>
 * 요약 파일에는 설정(payments · poll_ms · limit_ms)만 있다 - 쌍은 r129-summary.py 가 k6 로그에서 읽는다
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const PAYMENTS = parseInt(__ENV.PAYMENTS || '100', 10);
const POLL_MS = parseInt(__ENV.POLL_MS || '50', 10);
const LIMIT_MS = parseInt(__ENV.LIMIT_MS || '15000', 10);
const PRODUCT_ID = __ENV.PRODUCT_ID;
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'k6-summary.json';
const ORDERS = JSON.parse(open(__ENV.ORDERS_FILE || 'payonly-orders.json'));

export const options = {
    setupTimeout: '600s',
    scenarios: {
        sequence: {
            executor: 'per-vu-iterations', vus: 1, iterations: PAYMENTS,
            // 결제마다 한도(LIMIT_MS)까지 기다려도 끝나도록 여유를 둔다
            maxDuration: Math.ceil(PAYMENTS * (LIMIT_MS / 1000 + 5) + 120) + 's',
        },
    },
};

export function setup() {
    if (ORDERS.orders.length < PAYMENTS) throw new Error('주문이 모자란다: ' + ORDERS.orders.length + '/' + PAYMENTS);
    const reqs = [];
    for (let k = 1; k <= ORDERS.users; k++) {
        reqs.push(['POST', BASE_URL + '/auth/login', JSON.stringify({email: 'seed_' + k + '@test.com', password: PASSWORD}),
            {headers: {'Content-Type': 'application/json'}, tags: {name: 'setup_login'}}]);
    }
    const tokens = http.batch(reqs).map((r) => {
        if (r.status !== 200) throw new Error('로그인 실패 status=' + r.status + ' body=' + r.body);
        return r.json('accessToken');
    });
    console.log('setup: logins=' + tokens.length + ' orders=' + ORDERS.orders.length + ' payments=' + PAYMENTS);
    return {tokens};
}

function readStock() {
    const r = http.get(BASE_URL + '/product/' + PRODUCT_ID, {tags: {name: 'product_detail'}});
    return r.status === 200 ? r.json('stock') : null;
}

export default function (data) {
    const idx = exec.scenario.iterationInTest;
    const order = ORDERS.orders[idx];
    const before = readStock();
    const paymentKey = 'lt_pk_sv_' + idx + '_' + Date.now();
    const r = http.post(BASE_URL + '/payment/confirm', JSON.stringify({paymentKey, orderId: order.orderId, amount: order.amount}),
        {headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + data.tokens[order.u - 1]}, tags: {name: 'confirm'}, timeout: '120s'});
    const confirmEnd = Date.now();
    let seen = null;
    let stockSeen = null;
    let polls = 0;
    // 확정이 실패했거나 읽기 전 수량이 없으면 낮아질 근거가 없다 - 쌍을 적되 폴링하지 않는다(요약이 상태를 보인다)
    if (r.status === 200 && before !== null) {
        while (Date.now() - confirmEnd < LIMIT_MS) {
            const now = readStock();
            polls++;
            if (now !== null && now < before) {
                seen = Date.now();
                stockSeen = now;
                break;
            }
            sleep(POLL_MS / 1000);
        }
    }
    console.log('PAIR i=' + idx + ' status=' + r.status + ' before=' + before + ' end=' + confirmEnd + ' seen=' + seen + ' seen_stock=' + stockSeen + ' polls=' + polls);
}

export function handleSummary() {
    const out = {payments: PAYMENTS, poll_ms: POLL_MS, limit_ms: LIMIT_MS, product_id: PRODUCT_ID};
    return {[SUMMARY_FILE]: JSON.stringify(out)};
}
