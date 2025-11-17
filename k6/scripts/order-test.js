import http from 'k6/http';
import { check, group, sleep } from 'k6';

// Order 도메인 부하 테스트
// 실제 API:
// - GET /v1/cart (Cart inquiry)
// - POST /v1/cart/add (Add to cart with UUID productId)
// - PATCH /v1/cart/increase-quantity, /v1/cart/decrease-quantity (Cart quantity management)
// - POST /v1/order (Create order with CreateOrderRequest - only couponId)
// - GET /v1/order/{orderId} (Order detail with UUID orderId)
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // 램프업: 10 VU
    { duration: '1m', target: 30 },    // 증가: 30 VU
    { duration: '1m', target: 50 },    // 최대: 50 VU (주문은 트래픽이 적음)
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

// 테스트용 UUID (실제 DB에 있는 상품 ID로 변경 필요)
const TEST_PRODUCT_ID = '550e8400-e29b-41d4-a716-446655440000';

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
  // 각 VU마다 한 번씩 로그인
  if (!authToken) {
    login();
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${authToken}`,
  };

  group('1. 장바구니 조회', () => {
    const cartRes = http.get(`${BASE_URL}/cart`, { headers });

    check(cartRes, {
      'cart status is 200': (r) => r.status === 200,
      'cart has cartId': (r) => r.json('cartId') !== null,
      'cart response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(1);

  group('2. 장바구니에 상품 추가', () => {
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

  sleep(1);

  group('3. 장바구니 수량 증가', () => {
    const increaseQuantityPayload = JSON.stringify({
      productId: TEST_PRODUCT_ID,
    });

    const increaseRes = http.patch(`${BASE_URL}/cart/increase-quantity`, increaseQuantityPayload, {
      headers,
    });

    check(increaseRes, {
      'increase status 200 or 400': (r) => r.status === 200 || r.status === 400,
      'increase response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(1);

  group('4. 주문 생성', () => {
    // CreateOrderRequest only contains couponId (optional)
    const createOrderPayload = JSON.stringify({
      couponId: null,
    });

    const orderRes = http.post(`${BASE_URL}/order`, createOrderPayload, {
      headers,
    });

    check(orderRes, {
      'order create status 201 or 400': (r) => r.status === 201 || r.status === 400,
      'order has orderId': (r) => r.status !== 201 || r.json('orderId') !== null,
      'order response time < 2000ms': (r) => r.timings.duration < 2000,
    });
  });

  sleep(2);
}
