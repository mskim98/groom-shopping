import http from 'k6/http';
import { check, group, sleep } from 'k6';

// Product + Order + Payment 통합 부하 테스트
// 실제 사용자 플로우: 상품 조회 → 장바구니 추가 → 주문 생성 → 결제
export const options = {
  stages: [
    { duration: '30s', target: 20 },   // 램프업: 20 VU
    { duration: '1m', target: 50 },    // 증가: 50 VU
    { duration: '2m', target: 100 },   // 최대: 100 VU
    { duration: '1m', target: 50 },    // 감소: 50 VU
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

// 테스트용 UUID
const TEST_PRODUCT_ID = '550e8400-e29b-41d4-a716-446655440000';
const TEST_ORDER_ID = '550e8400-e29b-41d4-a716-446655440001';

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
  // 첫 요청에 로그인
  if (!authToken) {
    login();
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${authToken}`,
  };

  // ==================== PRODUCT 도메인 ====================
  group('Product: 상품 목록 조회 (페이지네이션)', () => {
    const listRes = http.get(`${BASE_URL}/product?page=0&size=20`, { headers });

    check(listRes, {
      'product list status is 200': (r) => r.status === 200,
      'product list has content': (r) => r.json('content') !== null,
      'product list response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.5);

  group('Product: 상품 상세 조회', () => {
    const detailRes = http.get(`${BASE_URL}/product/${TEST_PRODUCT_ID}`, { headers });

    check(detailRes, {
      'product detail status is 200 or 404': (r) => r.status === 200 || r.status === 404,
      'product detail response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.5);

  // ==================== ORDER 도메인 ====================
  group('Order: 장바구니 조회', () => {
    const cartRes = http.get(`${BASE_URL}/cart`, { headers });

    check(cartRes, {
      'cart status is 200': (r) => r.status === 200,
      'cart has cartId': (r) => r.json('cartId') !== null,
      'cart response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.5);

  group('Order: 장바구니에 상품 추가', () => {
    const addToCartPayload = JSON.stringify({
      productId: TEST_PRODUCT_ID,
      quantity: 1,
    });

    const addRes = http.post(`${BASE_URL}/cart/add`, addToCartPayload, {
      headers,
    });

    check(addRes, {
      'add to cart status 200 or 400': (r) => r.status === 200 || r.status === 400,
      'add response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(0.5);

  group('Order: 주문 생성', () => {
    // CreateOrderRequest에는 couponId만 포함됨
    const createOrderPayload = JSON.stringify({
      couponId: null,
    });

    const orderRes = http.post(`${BASE_URL}/order`, createOrderPayload, {
      headers,
    });

    check(orderRes, {
      'order create status 201 or 400': (r) => r.status === 201 || r.status === 400,
      'order response time < 2000ms': (r) => r.timings.duration < 2000,
    });
  });

  sleep(1);

  // ==================== PAYMENT 도메인 ====================
  group('Payment: 내 결제 내역 조회', () => {
    const myPaymentsRes = http.get(`${BASE_URL}/payment/my`, { headers });

    check(myPaymentsRes, {
      'payment list status is 200': (r) => r.status === 200,
      'payment list is array': (r) => Array.isArray(r.json()),
      'payment list response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(0.5);

  group('Payment: 주문별 결제 조회', () => {
    const paymentByOrderRes = http.get(`${BASE_URL}/payment/order/${TEST_ORDER_ID}`, { headers });

    check(paymentByOrderRes, {
      'payment by order status 200 or 404': (r) => r.status === 200 || r.status === 404,
      'payment by order response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(0.5);

  group('Payment: 결제 승인 (테스트)', () => {
    // Test payment confirmation - Toss API를 호출하지 않음
    const confirmPaymentPayload = JSON.stringify({
      paymentKey: `test_key_${__VU}_${__ITER}`,
      orderId: TEST_ORDER_ID,
      amount: 50000,
    });

    const confirmRes = http.post(`${BASE_URL}/payment/confirm/test`, confirmPaymentPayload, {
      headers,
    });

    check(confirmRes, {
      'test confirm status 200 or 400': (r) => r.status === 200 || r.status === 400,
      'test confirm response time < 2000ms': (r) => r.timings.duration < 2000,
    });
  });

  sleep(2);
}
