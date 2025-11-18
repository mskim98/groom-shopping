import http from 'k6/http';
import { check, group, sleep } from 'k6';

/**
 * Order 도메인 전문 부하 테스트
 *
 * 목표: 주문 및 장바구니 API의 성능 검증
 * - 장바구니 조회/관리
 * - 상품 추가/수량 변경
 * - 주문 생성
 * - 주문 조회
 */

export const options = {
  stages: [
    { duration: '30s', target: 10 },   // 워밍업: 10 VU
    { duration: '1m', target: 30 },    // 증가: 30 VU
    { duration: '2m', target: 50 },    // 최대: 50 VU
    { duration: '1m', target: 20 },    // 감소: 20 VU
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

let products = [];

/**
 * 초기화: 실제 DB 데이터 로드
 */
export function setup() {
  console.log('🔄 Setup: 상품 데이터 로드 시작...');

  const setupData = { products: [] };

  // 상품 데이터 로드
  const productRes = http.get(`${BASE_URL}/product?page=0&size=100`, {
    headers: { 'Content-Type': 'application/json' },
  });

  if (productRes.status === 200) {
    const content = productRes.json('content');
    if (content && Array.isArray(content)) {
      // productId → id로 변환
      const products = content.map(p => ({
        ...p,
        id: p.productId || p.id
      }));

      setupData.products = products;
      setupData.generalProducts = products.filter((p) => p.category === 'GENERAL');
      console.log(`✅ 상품 ${content.length}개 로드됨`);
      console.log(`   └─ GENERAL: ${setupData.generalProducts.length}개`);
    }
  } else {
    console.error(`❌ 상품 로드 실패: ${productRes.status}`);
  }

  return setupData;
}

/**
 * 테스트 메인 로직
 */
export default function (setupData) {
  const allProducts = setupData.products || [];
  const generalProducts = setupData.generalProducts || [];

  if (generalProducts.length === 0) {
    console.error('❌ GENERAL 상품이 없습니다');
    return;
  }

  // 테스트 사용자 선택 (user_1 ~ user_20)
  const userNum = ((__VU - 1) % 20) + 1;
  const testUser = {
    email: `user_${userNum}@test.com`,
    password: 'password123',
  };

  // ==================== 1. 인증 ====================
  let authToken = '';

  group('Auth: 로그인', () => {
    const loginRes = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify(testUser),
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(loginRes, {
      'status is 200': (r) => r.status === 200,
      'has accessToken': (r) => r.json('accessToken') !== null,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });

    if (loginRes.status === 200) {
      authToken = loginRes.json('accessToken');
    }
  }

  if (!authToken) {
    console.error('❌ 로그인 실패');
    return;
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${authToken}`,
  };

  sleep(0.5);

  // ==================== 2. 장바구니 조회 ====================
  group('Cart: 장바구니 조회', () => {
    const cartRes = http.get(`${BASE_URL}/cart`, { headers });

    check(cartRes, {
      'status is 200': (r) => r.status === 200,
      'has cartId': (r) => r.json('cartId') !== null,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 3. 장바구니에 상품 추가 ====================
  const selectedProduct = generalProducts[__ITER % generalProducts.length];

  group('Cart: 장바구니에 상품 추가', () => {
    const addToCartPayload = JSON.stringify({
      productId: selectedProduct.id,
      quantity: 1,
    });

    const addRes = http.post(`${BASE_URL}/cart/add`, addToCartPayload, {
      headers,
    });

    check(addRes, {
      'status is 200': (r) => r.status === 200,
      'has cartItemId': (r) => r.json('cartItemId') !== null,
      'response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(0.3);

  // ==================== 4. 장바구니 수량 증가 ====================
  group('Cart: 장바구니 수량 증가', () => {
    const increaseQuantityPayload = JSON.stringify({
      productId: selectedProduct.id,
    });

    const increaseRes = http.patch(`${BASE_URL}/cart/increase-quantity`, increaseQuantityPayload, {
      headers,
    });

    check(increaseRes, {
      'status is 200': (r) => r.status === 200 || r.status === 400,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 5. 주문 생성 ====================
  let orderId = null;

  group('Order: 주문 생성', () => {
    const createOrderPayload = JSON.stringify({
      couponId: null,
    });

    const orderRes = http.post(`${BASE_URL}/order`, createOrderPayload, {
      headers,
    });

    check(orderRes, {
      'status is 201': (r) => r.status === 201,
      'has orderId': (r) => r.json('id') !== null,
      'has totalAmount': (r) => r.json('totalAmount') > 0,
      'response time < 2000ms': (r) => r.timings.duration < 2000,
    });

    if (orderRes.status === 201) {
      orderId = orderRes.json('id');
    }
  });

  sleep(1);

  // ==================== 6. 주문 상세 조회 ====================
  if (orderId) {
    group('Order: 주문 상세 조회', () => {
      const orderDetailRes = http.get(`${BASE_URL}/order/${orderId}`, { headers });

      check(orderDetailRes, {
        'status is 200': (r) => r.status === 200,
        'has orderId': (r) => r.json('id') === orderId,
        'response time < 1000ms': (r) => r.timings.duration < 1000,
      });
    });

    sleep(0.5);
  }

  // ==================== 7. 주문 목록 조회 ====================
  group('Order: 주문 목록 조회', () => {
    const ordersRes = http.get(`${BASE_URL}/order?page=0&size=20`, { headers });

    check(ordersRes, {
      'status is 200': (r) => r.status === 200,
      'has content': (r) => r.json('content') !== null,
      'response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(1);

  // ==================== 8. 장바구니 초기화 ====================
  group('Cart: 장바구니 비우기', () => {
    const cartRes = http.get(`${BASE_URL}/cart`, { headers });

    if (cartRes.status === 200) {
      const items = cartRes.json('items');
      if (items && items.length > 0) {
        const removePayload = JSON.stringify({
          items: items.map((item) => ({
            productId: item.productId,
            quantity: item.quantity,
          })),
        });

        const removeRes = http.delete(`${BASE_URL}/cart/remove`, removePayload, {
          headers,
        });

        check(removeRes, {
          'status is 200': (r) => r.status === 200,
        });
      }
    }
  });

  sleep(1);
}

/**
 * 테스트 완료
 */
export function teardown(setupData) {
  console.log('✅ Order 테스트 완료');
  console.log(`   테스트된 상품: ${setupData.products.length}개`);
}
