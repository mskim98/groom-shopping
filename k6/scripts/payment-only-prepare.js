import http from 'k6/http';

/**
 * 결제 전용 단계 측정의 준비 - 측정 전에 주문을 미리 만들어 파일에 적는다(측정 구간 밖)
 *
 * 주문은 실제 API 로 만든다: 사용자마다 장바구니에 플래시 상품 1개 담기 → 주문(결제 행이 함께 생긴다) → 담은 항목 제거.
 * 한 라운드에 USERS 명이 동시에 한 건씩 만들고(서버당 동시 요청이 트랜잭션 격벽 40 을 넘지 않게 USERS 는 작게 둔다) ORDERS_COUNT 건이 모일 때까지 돈다
 * 결과 파일(ORDERS_FILE): {users, orders: [{u: 시드 사용자 번호, orderId, amount}]} - 결제 확정 스크립트(payment-only-steps.js)가 읽는다
 *
 * 환경 변수: ORDERS_COUNT(필수) · ORDERS_FILE(필수) · USERS(60) · BASE_URL · FLASH_COUNT(20) · LT_PASSWORD
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const ORDERS_COUNT = parseInt(__ENV.ORDERS_COUNT || '0', 10);
const ORDERS_FILE = __ENV.ORDERS_FILE || 'payonly-orders.json';
const USERS = parseInt(__ENV.USERS || '60', 10);
const FLASH_COUNT = parseInt(__ENV.FLASH_COUNT || '20', 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';

export const options = {
    vus: 1,
    iterations: 1,
    setupTimeout: '900s',
    // http.batch 의 동시 요청 수 기본값(20 · 호스트당 6)이면 라운드가 느려진다
    batch: USERS,
    batchPerHost: USERS,
};

function jsonHeaders(token) {
    const h = {'Content-Type': 'application/json'};
    if (token) h.Authorization = 'Bearer ' + token;
    return h;
}

function productOf(n) {
    return 'f1a50000-0000-4000-8000-' + String(n).padStart(12, '0');
}

function ok(r) {
    return r.status >= 200 && r.status < 300;
}

export function setup() {
    const logins = http.batch(Array.from({length: USERS}, (_, i) => ['POST', BASE_URL + '/auth/login',
        JSON.stringify({email: 'seed_' + (i + 1) + '@test.com', password: PASSWORD}), {headers: jsonHeaders(null)}]));
    const tokens = logins.map((r) => {
        if (r.status !== 200) throw new Error('로그인 실패 status=' + r.status + ' body=' + r.body);
        return r.json('accessToken');
    });
    // 시드가 담아 둔 장바구니 항목을 비운다 - 남은 항목이 있으면 주문이 그 항목까지 담는다
    clearCarts(tokens, tokens.map((_, i) => i));

    const orders = [];
    let rounds = 0;
    let failedRounds = 0;
    while (orders.length < ORDERS_COUNT) {
        rounds++;
        if (rounds > ORDERS_COUNT) throw new Error('주문이 모이지 않는다: ' + orders.length + '/' + ORDERS_COUNT);
        const productId = productOf(1 + (rounds % FLASH_COUNT));
        const add = http.batch(tokens.map((t) => ['POST', BASE_URL + '/cart/add', JSON.stringify({productId, quantity: 1}), {headers: jsonHeaders(t)}]));
        const ordered = http.batch(tokens.map((t, i) => ok(add[i])
            ? ['POST', BASE_URL + '/order', JSON.stringify({couponId: null}), {headers: jsonHeaders(t)}]
            : ['GET', BASE_URL + '/cart', null, {headers: jsonHeaders(t)}]));
        let made = 0;
        ordered.forEach((r, i) => {
            if (ok(add[i]) && r.status === 201 && orders.length < ORDERS_COUNT) {
                orders.push({u: i + 1, orderId: r.json('orderId'), amount: r.json('totalAmount')});
                made++;
            }
        });
        if (made < USERS) failedRounds++;
        // 담은 항목을 뺀다. 담기가 2xx 가 아니어도 서버에 들어갔는지 모르는 경우가 있어 모든 사용자에게 보낸다(없으면 4xx 로 끝난다)
        const removed = http.batch(tokens.map((t) => ['DELETE', BASE_URL + '/cart/remove',
            JSON.stringify({items: [{productId, quantity: 1}]}), {headers: jsonHeaders(t)}]));
        // 제거가 5xx 면 남은 항목이 다음 주문에 섞인다 - 그런 사용자의 장바구니는 조회해서 비운다
        const retry = [];
        removed.forEach((r, i) => { if (r.status >= 500 || r.status === 0) retry.push(i); });
        if (retry.length > 0) clearCarts(tokens, retry);
    }
    console.log('prepare: orders=' + orders.length + ' users=' + USERS + ' rounds=' + rounds + ' rounds_with_failures=' + failedRounds);
    return {users: USERS, orders};
}

function clearCarts(tokens, indexes) {
    const carts = http.batch(indexes.map((i) => ['GET', BASE_URL + '/cart', null, {headers: jsonHeaders(tokens[i])}]));
    const clears = [];
    carts.forEach((c, k) => {
        const items = c.status === 200 ? (c.json('items') || []) : [];
        if (items.length > 0) {
            clears.push(['DELETE', BASE_URL + '/cart/remove',
                JSON.stringify({items: items.map((it) => ({productId: it.productId, quantity: it.quantity}))}), {headers: jsonHeaders(tokens[indexes[k]])}]);
        }
    });
    if (clears.length > 0) http.batch(clears);
}

export default function () {}

export function handleSummary(data) {
    const d = data.setup_data || {users: USERS, orders: []};
    return {
        [ORDERS_FILE]: JSON.stringify(d),
        stdout: 'prepared ' + d.orders.length + ' orders for ' + d.users + ' users\n',
    };
}
