import http from 'k6/http';
import {check, sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';

/**
 * ① 재고 차감 동시성 측정 (낙관적 락 + Redis 선점)
 *
 * 시나리오: 다수 사용자가 같은 "핫 상품" 1개를 동시에 결제 확정 → product.version 낙관적 락 경합 유발.
 * 측정: 결제 확정(POST /payment/confirm) 응답시간 p95/p99, 성공/품절(선점차단)/기타 실패 분포.
 * 정합성: 테스트 후 DB 최종 재고 == 초기재고 - confirm_success (런북의 SQL 로 검증).
 *
 * 전제(런북 참조):
 *   - Toss API 는 WireMock healthy(200) 로 대체(payment.toss.api-url 오버라이드).
 *   - HOT_PRODUCT_ID = AVAILABLE·재고 known 인 GENERAL 상품. Redis 재고 워밍업된 상태.
 *   - 각 VU 는 고유 부하유저에 고정(VUS <= USERS) → 사용자별 장바구니 경합 없음.
 *
 * 실행 예:
 *   k6 run -e HOT_PRODUCT_ID=<550e8400-e29b-41d4-a716-446655440000> -e USERS=200 -e VUS=200 -e ITERATIONS=10000 \
 *          k6/scripts/stock-concurrency-test.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const HOT_PRODUCT_ID = __ENV.HOT_PRODUCT_ID; // 필수
const USERS = parseInt(__ENV.USERS || '200', 10);
const VUS = parseInt(__ENV.VUS || '200', 10);
const ITERATIONS = parseInt(__ENV.ITERATIONS || '10000', 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';

// ── 커스텀 지표 ───────────────────────────────────────────────
const confirmLatency = new Trend('confirm_latency', true); // 결제 확정 응답시간(ms)
const confirmSuccess = new Counter('confirm_success');     // 확정 성공(재고 차감 완료)
const confirmBlocked = new Counter('confirm_blocked');     // 선점 차단/품절(INSUFFICIENT_STOCK)
const confirmConflict = new Counter('confirm_conflict');   // 낙관적 락 최종 충돌(PRODUCT_STOCK_CONFLICT)
const confirmOther = new Counter('confirm_other_fail');    // 기타 실패

export const options = {
    scenarios: {
        hot_product_rush: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS, // 총 확정 시도 횟수(정합성 비교 기준)
            maxDuration: '10m',
        },
    },
    thresholds: {
        // 이력서 목표(참고): p95 720→180ms, p99 240ms. 실측 후 갱신.
        confirm_latency: ['p(95)<300', 'p(99)<500'],
    },
};

// VU 별 토큰 캐시(각 VU 는 독립 JS 런타임 → 모듈 변수는 VU 로컬)
let cachedToken = null;
let cachedUser = null;

export function setup() {
    if (!HOT_PRODUCT_ID) {
        throw new Error('HOT_PRODUCT_ID 환경변수가 필요합니다. (예: -e HOT_PRODUCT_ID=<uuid>)');
    }
    console.log(`🔄 부하 유저 ${USERS}명 등록(이미 있으면 무시)...`);
    let created = 0;
    for (let i = 1; i <= USERS; i++) {
        const res = http.post(`${BASE_URL}/auth/signup`, JSON.stringify({
            email: `lt_${i}@test.com`,
            password: PASSWORD,
            name: `lt_user_${i}`,
        }), {headers: {'Content-Type': 'application/json'}});
        if (res.status === 200 || res.status === 201) created++;
    }
    console.log(`✅ 신규 등록 ${created}명 (나머지는 기존 재사용). HOT_PRODUCT_ID=${HOT_PRODUCT_ID}`);
    return {hotProductId: HOT_PRODUCT_ID};
}

function login(email) {
    const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({email, password: PASSWORD}),
        {headers: {'Content-Type': 'application/json'}});
    return res.status === 200 ? res.json('accessToken') : null;
}

export default function (data) {
    // VU → 고유 유저 고정(장바구니 경합 방지). VUS <= USERS 전제.
    const userNum = ((__VU - 1) % USERS) + 1;
    const email = `lt_${userNum}@test.com`;

    if (!cachedToken || cachedUser !== email) {
        cachedToken = login(email);
        cachedUser = email;
    }
    if (!cachedToken) {
        confirmOther.add(1);
        return;
    }
    const headers = {'Content-Type': 'application/json', Authorization: `Bearer ${cachedToken}`};

    // 0~2) 카트 정리 → 1개 담기 → 주문 생성.
    //   ⚠️ 서버는 결제 확정 200 응답 후 @Async 로 장바구니를 비운다(PaymentNotificationService.clearCartItems).
    //   직전 반복의 '비동기 카트비우기'가 이번 반복의 add 직후 백그라운드에서 실행되면 주문이 '빈 카트'로
    //   생성 실패한다(시스템 버그가 아닌 테스트 타이밍 경합). → 매 시도마다 카트를 '정확히 1개'로 리셋한 뒤
    //   주문을 시도하고, 빈 카트 실패 시 짧게 대기 후 재시도해 경합을 흡수한다(수량 1 불변 유지 → 재고 정합 비교 보존).
    let orderRes = null;
    for (let attempt = 0; attempt < 5; attempt++) {
        // 카트의 핫 상품 수량을 0으로 비운 뒤 1개만 담아 '항상 수량 1' 보장(재시도 시 중복 적재로 인한 2개 차감 방지)
        const cartRes = http.get(`${BASE_URL}/cart`, {headers});
        if (cartRes.status === 200) {
            const items = cartRes.json('items') || [];
            const hot = items.find((it) => it.productId === data.hotProductId);
            if (hot && hot.quantity > 0) {
                http.del(`${BASE_URL}/cart/remove`,
                    JSON.stringify({items: [{productId: data.hotProductId, quantity: hot.quantity}]}), {headers});
            }
        }
        http.post(`${BASE_URL}/cart/add`, JSON.stringify({productId: data.hotProductId, quantity: 1}), {headers});

        // 주문 생성 → orderId, totalAmount
        orderRes = http.post(`${BASE_URL}/order`, JSON.stringify({couponId: null}), {headers});
        if (orderRes.status === 201) break;
        sleep(0.05); // 비동기 카트비우기가 끝나길 짧게 대기 후 재시도
    }
    if (!orderRes || orderRes.status !== 201) {
        confirmOther.add(1);
        return;
    }
    const orderId = orderRes.json('orderId');
    const amount = orderRes.json('totalAmount');

    // 3) 결제 확정(재고 차감 트리거). paymentKey 는 요청마다 고유.
    const paymentKey = `lt_pk_${__VU}_${__ITER}_${Date.now()}`;
    const confirmRes = http.post(`${BASE_URL}/payment/confirm`,
        JSON.stringify({paymentKey, orderId, amount}), {headers});

    confirmLatency.add(confirmRes.timings.duration);
    const body = confirmRes.body || '';
    if (confirmRes.status === 200) {
        confirmSuccess.add(1);
    } else if (body.includes('INSUFFICIENT_STOCK')) {
        confirmBlocked.add(1);
    } else if (body.includes('PRODUCT_STOCK_CONFLICT')) {
        confirmConflict.add(1);
    } else {
        confirmOther.add(1);
    }
    check(confirmRes, {'confirm 처리됨(200/4xx)': (r) => r.status === 200 || (r.status >= 400 && r.status < 500)});
}

export function teardown() {
    console.log('✅ 재고 동시성 측정 완료. 정합성은 런북의 SQL 로 (초기재고 - confirm_success == 최종재고) 검증.');
}
