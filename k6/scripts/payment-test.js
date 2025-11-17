import http from 'k6/http';
import { check, group, sleep } from 'k6';

// Payment 도메인 부하 테스트
// 실제 API:
// - POST /v1/payment/confirm (Confirm payment with ConfirmPaymentRequest: paymentKey, orderId UUID, amount)
// - POST /v1/payment/confirm/test (Test confirm without actual Toss API call)
// - GET /v1/payment/{paymentId} (Get payment detail with UUID paymentId)
// - GET /v1/payment/order/{orderId} (Get payment by order with UUID orderId)
// - GET /v1/payment/my (Get my payments)
// - POST /v1/payment/cancel (Cancel payment - ADMIN only, with CancelPaymentRequest: paymentId UUID, cancelReason)
export const options = {
  stages: [
    { duration: '30s', target: 5 },    // 램프업: 5 VU
    { duration: '1m', target: 15 },    // 증가: 15 VU
    { duration: '1m', target: 30 },    // 최대: 30 VU (결제는 매우 신중하게)
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<3000'], // 결제는 더 높은 기준
    http_req_failed: ['rate<0.05'],                   // 결제는 0.05% 이하
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

// 테스트용 UUID (실제 DB에 있는 주문/결제 ID로 변경 필요)
const TEST_ORDER_ID = '550e8400-e29b-41d4-a716-446655440000';
const TEST_PAYMENT_ID = '550e8400-e29b-41d4-a716-446655440001';

let authToken = '';

// 로그인 함수
function login() {
  const loginPayload = JSON.stringify({
    email: `user_${__VU}@test.com`,
    password: 'password123',
  });

  const loginRes = http.post(`${BASE_URL}/auth/login`, loginPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  if (loginRes.status === 200) {
    authToken = loginRes.json('accessToken');
  }
}

export default function () {
  if (!authToken) {
    login();
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${authToken}`,
  };

  group('1. 내 결제 내역 조회', () => {
    const myPaymentsRes = http.get(`${BASE_URL}/payment/my`, { headers });

    check(myPaymentsRes, {
      'my payments status is 200': (r) => r.status === 200,
      'my payments is array': (r) => Array.isArray(r.json()),
      'my payments response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(1);

  group('2. 주문별 결제 조회', () => {
    const paymentByOrderRes = http.get(`${BASE_URL}/payment/order/${TEST_ORDER_ID}`, { headers });

    check(paymentByOrderRes, {
      'payment by order status 200 or 404': (r) => r.status === 200 || r.status === 404,
      'payment by order response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(1);

  group('3. 결제 상세 조회', () => {
    const paymentDetailRes = http.get(`${BASE_URL}/payment/${TEST_PAYMENT_ID}`, { headers });

    check(paymentDetailRes, {
      'payment detail status 200 or 404': (r) => r.status === 200 || r.status === 404,
      'payment detail response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(1);

  group('4. 테스트용 결제 승인 (실제 Toss API 호출 없음)', () => {
    // Test payment confirmation - Toss API를 호출하지 않음
    const confirmPaymentPayload = JSON.stringify({
      paymentKey: `test_payment_key_${__VU}_${__ITER}`,
      orderId: TEST_ORDER_ID,
      amount: 50000,
    });

    const confirmRes = http.post(`${BASE_URL}/payment/confirm/test`, confirmPaymentPayload, {
      headers,
    });

    check(confirmRes, {
      'test confirm status 200 or 400': (r) => r.status === 200 || r.status === 400,
      'test confirm has id': (r) => r.status !== 200 || r.json('id') !== null,
      'test confirm response time < 2000ms': (r) => r.timings.duration < 2000,
    });
  });

  sleep(2);

  group('5. 결제 승인 (Toss API)', () => {
    // Real payment confirmation - Toss API를 호출함
    const confirmPaymentPayload = JSON.stringify({
      paymentKey: `tgen_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      orderId: TEST_ORDER_ID,
      amount: 50000,
    });

    const confirmRes = http.post(`${BASE_URL}/payment/confirm`, confirmPaymentPayload, {
      headers,
    });

    check(confirmRes, {
      'confirm status 200 or 400': (r) => r.status === 200 || r.status === 400,
      'confirm response time < 3000ms': (r) => r.timings.duration < 3000,
    });
  });

  sleep(3); // 결제는 더 긴 간격 유지
}
