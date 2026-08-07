import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

/**
 * ② Resilience4j Circuit Breaker(Toss) 응답시간 측정
 *
 * 시나리오: Toss(WireMock degraded = 5s 지연 + 500)로 결제 확정을 지속 요청 → 회로가 OPEN 되면
 * 이후 호출은 Toss 를 거치지 않고 즉시 실패(fast-fail).
 * 측정: 결제 확정(POST /payment/confirm) 응답시간 분포. CB OFF(baseline) vs CB ON(A/B).
 *   - CB OFF: 매 호출이 Toss timeout(5s) × 재시도 → p99 수 초(이력서 12s 부근)
 *   - CB ON : 회로 OPEN 후 fast-fail → p99 수백 ms(이력서 250ms 목표)
 *
 * A/B 토글(런북 참조): application yml 의
 *   resilience4j.circuitbreaker.instances.toss.minimum-number-of-calls 를 매우 크게(예: 100000) 두면
 *   사실상 회로가 안 열려 baseline(OFF) 측정이 된다.
 *
 * 전제: payment.toss.api-url → WireMock degraded. HOT_PRODUCT_ID = AVAILABLE·재고 충분(완료가 없으므로 큰 값 권장).
 *
 * 실행 예:
 *   k6 run -e HOT_PRODUCT_ID=<uuid> -e USERS=50 -e VUS=50 -e DURATION=3m \
 *          k6/scripts/circuit-breaker-test.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const HOT_PRODUCT_ID = __ENV.HOT_PRODUCT_ID; // 필수
const USERS = parseInt(__ENV.USERS || '50', 10);
const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '3m';
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';

// ── 커스텀 지표 ───────────────────────────────────────────────
const confirmLatency = new Trend('confirm_latency', true);      // 전체 확정 응답시간(ms)
const fastFailLatency = new Trend('fastfail_latency', true);    // fast-fail 로 추정(빠른 실패)
const slowFailLatency = new Trend('slowfail_latency', true);    // Toss 까지 갔다 실패(느린 실패)
const cbFastFail = new Counter('cb_fast_fail');                 // 빠른 실패 건수(회로 OPEN 추정)
const cbSlowFail = new Counter('cb_slow_fail');                 // 느린 실패 건수(Toss 호출 도달)

// fast-fail 판정 임계(ms). Toss timeout(5s)+재시도 대비 충분히 작은 값.
const FASTFAIL_THRESHOLD_MS = parseInt(__ENV.FASTFAIL_MS || '1500', 10);

export const options = {
  scenarios: {
    pg_degraded: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  // 의도된 장애 주입이라 http_req_failed 임계는 두지 않는다(전부 실패가 정상).
  thresholds: {
    confirm_latency: ['p(99)<99999'], // 측정용(임계 아님). 결과는 요약에서 확인.
  },
};

let cachedToken = null;
let cachedUser = null;

export function setup() {
  if (!HOT_PRODUCT_ID) {
    throw new Error('HOT_PRODUCT_ID 환경변수가 필요합니다. (예: -e HOT_PRODUCT_ID=<uuid>)');
  }
  for (let i = 1; i <= USERS; i++) {
    http.post(`${BASE_URL}/auth/signup`, JSON.stringify({
      email: `lt_${i}@test.com`, password: PASSWORD, name: `lt_user_${i}`,
    }), { headers: { 'Content-Type': 'application/json' } });
  }
  console.log(`✅ 부하 유저 준비 완료. HOT_PRODUCT_ID=${HOT_PRODUCT_ID} (Toss=WireMock degraded 전제)`);
  return { hotProductId: HOT_PRODUCT_ID };
}

function login(email) {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  return res.status === 200 ? res.json('accessToken') : null;
}

export default function (data) {
  const userNum = ((__VU - 1) % USERS) + 1;
  const email = `lt_${userNum}@test.com`;
  if (!cachedToken || cachedUser !== email) {
    cachedToken = login(email);
    cachedUser = email;
  }
  // 페이싱: 앱 장애/실패 시에도 초당 폭주(runaway)하지 않도록 매 반복 간격을 둔다.
  if (!cachedToken) { sleep(1); return; }
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${cachedToken}` };

  // 깨끗한 상태 보장: 카트에 핫 상품이 남아 있으면 제거(실패 누적 방지) → 주문 수량 항상 1
  const cartRes = http.get(`${BASE_URL}/cart`, { headers });
  if (cartRes.status === 200) {
    const items = cartRes.json('items') || [];
    const hot = items.find((it) => it.productId === data.hotProductId);
    if (hot && hot.quantity > 0) {
      http.del(`${BASE_URL}/cart/remove`,
        JSON.stringify({ items: [{ productId: data.hotProductId, quantity: hot.quantity }] }), { headers });
    }
  }
  // 매 반복 새 주문(이전 결제는 실패로 FAILED 가 되므로 재확정 불가)
  http.post(`${BASE_URL}/cart/add`, JSON.stringify({ productId: data.hotProductId, quantity: 1 }), { headers });
  const orderRes = http.post(`${BASE_URL}/order`, JSON.stringify({ couponId: null }), { headers });
  if (orderRes.status !== 201) { sleep(1); return; }
  const orderId = orderRes.json('orderId');
  const amount = orderRes.json('totalAmount');

  const paymentKey = `cb_pk_${__VU}_${__ITER}_${Date.now()}`;
  const res = http.post(`${BASE_URL}/payment/confirm`,
    JSON.stringify({ paymentKey, orderId, amount }), { headers });

  const dur = res.timings.duration;
  confirmLatency.add(dur);
  // 상태코드로는 둘 다 5xx 로 보일 수 있어 응답시간으로 fast-fail/slow-fail 을 구분(=핵심 측정값).
  if (dur <= FASTFAIL_THRESHOLD_MS) {
    fastFailLatency.add(dur);
    cbFastFail.add(1);
  } else {
    slowFailLatency.add(dur);
    cbSlowFail.add(1);
  }
  sleep(0.5); // 페이싱(runaway 방지)
}

export function teardown() {
  console.log('✅ 서킷브레이커 측정 완료. fastfail_latency/slowfail_latency 와 cb_fast_fail 비율로 CB 효과 확인.');
  console.log('   baseline(OFF) 비교는 minimum-number-of-calls 를 크게 둔 설정으로 동일 시나리오 재실행.');
}
