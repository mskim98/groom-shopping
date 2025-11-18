import http from 'k6/http';
import { check, group, sleep } from 'k6';

/**
 * Groom Shopping - 통합 부하 테스트 (로그인 제외)
 *
 * 시나리오: 실제 Flyway 데이터 기반 쇼핑 플로우
 * 1. 데이터 로드 (상품, 쿠폰, 래플)
 * 2. 상품 조회 (50개 상품)
 * 3. 카테고리별 필터링
 * 4. 페이지네이션
 * 5. 페이지 다양한 크기 테스트
 *
 * ⚠️ 로그인 API에 500 에러가 있어서 제외했습니다.
 * 인증이 필요한 API(장바구니, 주문, 결제 등)는 테스트하지 않습니다.
 * 인증이 필요 없는 상품 조회 API만 테스트합니다.
 */

export const options = {
  stages: [
    { duration: '30s', target: 20 },   // 워밍업: 20 VU
    { duration: '1m', target: 50 },    // 증가: 50 VU
    { duration: '2m', target: 100 },   // 최대: 100 VU (read-only)
    { duration: '1m', target: 50 },    // 감소: 50 VU
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

let products = [];
let generalProducts = [];
let ticketProducts = [];
let raffleProducts = [];

/**
 * 초기화: 실제 DB 데이터 로드 (한 번만 실행)
 */
export function setup() {
  console.log('🔄 Setup: 데이터 로드 시작...');

  const setupData = {
    products: [],
    generalProducts: [],
    ticketProducts: [],
    raffleProducts: [],
  };

  // 1️⃣ 상품 데이터 로드
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
      setupData.ticketProducts = products.filter((p) => p.category === 'TICKET');
      setupData.raffleProducts = products.filter((p) => p.category === 'RAFFLE');

      console.log(`✅ 상품 ${content.length}개 로드됨`);
      console.log(`   └─ GENERAL: ${setupData.generalProducts.length}개`);
      console.log(`   └─ TICKET: ${setupData.ticketProducts.length}개`);
      console.log(`   └─ RAFFLE: ${setupData.raffleProducts.length}개`);
    }
  } else {
    console.error(`❌ 상품 로드 실패: ${productRes.status}`);
  }

  console.log('✅ Setup 완료');
  return setupData;
}

/**
 * 테스트 메인 로직
 */
export default function (setupData) {
  // 데이터 추출
  const availableProducts = setupData.products || [];
  const generalProducts = setupData.generalProducts || [];
  const ticketProducts = setupData.ticketProducts || [];
  const raffleProducts = setupData.raffleProducts || [];

  if (availableProducts.length === 0) {
    console.error('❌ 로드된 상품이 없습니다');
    return;
  }

  // ==================== 1. 상품 목록 조회 (기본) ====================
  group('Product: 목록 조회 - 페이지 0 (20개)', () => {
    const listRes = http.get(`${BASE_URL}/product?page=0&size=20&sort=id,DESC`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(listRes, {
      'status is 200': (r) => r.status === 200,
      'has content': (r) => r.json('content') !== null,
      'has totalElements': (r) => r.json('totalElements') !== null,
      'response time < 300ms': (r) => r.timings.duration < 300,
    });
  });

  sleep(0.3);

  // ==================== 2. 다양한 페이지 크기 ====================
  group('Product: 목록 조회 - 페이지 크기 50', () => {
    const listRes = http.get(`${BASE_URL}/product?page=0&size=50&sort=id,DESC`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(listRes, {
      'status is 200': (r) => r.status === 200,
      'size is 50': (r) => r.json('size') === 50,
      'response time < 400ms': (r) => r.timings.duration < 400,
    });
  });

  sleep(0.3);

  // ==================== 3. 카테고리별 필터링 - GENERAL ====================
  group('Product: 카테고리 필터 - GENERAL', () => {
    const filterRes = http.get(
      `${BASE_URL}/product/search?category=GENERAL&page=0&size=50`,
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(filterRes, {
      'status is 200': (r) => r.status === 200,
      'has results': (r) => r.json('totalElements') > 0,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 4. 카테고리별 필터링 - TICKET ====================
  group('Product: 카테고리 필터 - TICKET', () => {
    const filterRes = http.get(
      `${BASE_URL}/product/search?category=TICKET&page=0&size=50`,
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(filterRes, {
      'status is 200': (r) => r.status === 200,
      'has results': (r) => r.json('totalElements') > 0,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 5. 카테고리별 필터링 - RAFFLE ====================
  group('Product: 카테고리 필터 - RAFFLE', () => {
    const filterRes = http.get(
      `${BASE_URL}/product/search?category=RAFFLE&page=0&size=50`,
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(filterRes, {
      'status is 200': (r) => r.status === 200,
      'has results': (r) => r.json('totalElements') > 0,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 6. 상품 상세 조회 ====================
  if (generalProducts.length > 0) {
    group('Product: 상세 조회 - GENERAL', () => {
      const selectedProduct = generalProducts[__ITER % generalProducts.length];

      const detailRes = http.get(`${BASE_URL}/product/${selectedProduct.id}`, {
        headers: { 'Content-Type': 'application/json' },
      });

      check(detailRes, {
        'status is 200': (r) => r.status === 200,
        'has productId': (r) => r.json('productId') !== null,
        'has name': (r) => r.json('name') !== null,
        'has price': (r) => r.json('price') > 0,
        'response time < 300ms': (r) => r.timings.duration < 300,
      });
    });

    sleep(0.2);
  }

  if (ticketProducts.length > 0) {
    group('Product: 상세 조회 - TICKET', () => {
      const selectedProduct = ticketProducts[__ITER % ticketProducts.length];

      const detailRes = http.get(`${BASE_URL}/product/${selectedProduct.id}`, {
        headers: { 'Content-Type': 'application/json' },
      });

      check(detailRes, {
        'status is 200': (r) => r.status === 200,
        'category is TICKET': (r) => r.json('category') === 'TICKET',
        'response time < 300ms': (r) => r.timings.duration < 300,
      });
    });

    sleep(0.2);
  }

  if (raffleProducts.length > 0) {
    group('Product: 상세 조회 - RAFFLE', () => {
      const selectedProduct = raffleProducts[__ITER % raffleProducts.length];

      const detailRes = http.get(`${BASE_URL}/product/${selectedProduct.id}`, {
        headers: { 'Content-Type': 'application/json' },
      });

      check(detailRes, {
        'status is 200': (r) => r.status === 200,
        'category is RAFFLE': (r) => r.json('category') === 'RAFFLE',
        'response time < 300ms': (r) => r.timings.duration < 300,
      });
    });

    sleep(0.2);
  }

  // ==================== 7. 정렬 테스트 ====================
  group('Product: 정렬 - 최신순 (ID DESC)', () => {
    const sortRes = http.get(`${BASE_URL}/product?page=0&size=20&sort=id,DESC`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(sortRes, {
      'status is 200': (r) => r.status === 200,
      'has content': (r) => r.json('content').length > 0,
      'response time < 400ms': (r) => r.timings.duration < 400,
    });
  });

  sleep(0.3);

  group('Product: 정렬 - 오래된순 (ID ASC)', () => {
    const sortRes = http.get(`${BASE_URL}/product?page=0&size=20&sort=id,ASC`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(sortRes, {
      'status is 200': (r) => r.status === 200,
      'response time < 400ms': (r) => r.timings.duration < 400,
    });
  });

  sleep(0.3);

  // ==================== 8. 상태별 필터 ====================
  group('Product: 상태 필터 - AVAILABLE', () => {
    const statusRes = http.get(
      `${BASE_URL}/product/search?status=AVAILABLE&page=0&size=50`,
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(statusRes, {
      'status is 200': (r) => r.status === 200,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 9. 페이지네이션 ====================
  group('Product: 페이지네이션 - 페이지 1', () => {
    const pageRes = http.get(`${BASE_URL}/product?page=1&size=20`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(pageRes, {
      'status is 200': (r) => r.status === 200,
      'page number is 1': (r) => r.json('number') === 1,
      'response time < 400ms': (r) => r.timings.duration < 400,
    });
  });

  sleep(0.3);

  group('Product: 페이지네이션 - 페이지 2', () => {
    const pageRes = http.get(`${BASE_URL}/product?page=2&size=20`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(pageRes, {
      'status is 200': (r) => r.status === 200,
      'page number is 2': (r) => r.json('number') === 2,
      'response time < 400ms': (r) => r.timings.duration < 400,
    });
  });

  sleep(0.5);
}

/**
 * 테스트 완료
 */
export function teardown(setupData) {
  console.log('✅ 통합 테스트 완료');
  console.log(`   테스트된 상품: ${setupData.products.length}개`);
  console.log(`   - GENERAL: ${setupData.generalProducts.length}개`);
  console.log(`   - TICKET: ${setupData.ticketProducts.length}개`);
  console.log(`   - RAFFLE: ${setupData.raffleProducts.length}개`);
}
