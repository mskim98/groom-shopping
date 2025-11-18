import http from 'k6/http';
import { check, group, sleep } from 'k6';

/**
 * Product 도메인 전문 부하 테스트
 *
 * 목표: 상품 조회 API의 응답 성능 검증
 * - 목록 조회 (페이지네이션, 정렬)
 * - 상세 조회
 * - 검색
 * - 카테고리별 필터링
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
 * 초기화: 실제 DB 데이터 로드
 */
export function setup() {
  console.log('🔄 Setup: 상품 데이터 로드 시작...');

  const setupData = { products: [] };

  // 상품 데이터 로드 (최대 100개)
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

  return setupData;
}

/**
 * 테스트 메인 로직
 */
export default function (setupData) {
  const allProducts = setupData.products || [];

  if (allProducts.length === 0) {
    console.error('❌ 로드된 상품이 없습니다');
    return;
  }

  const generalProducts = setupData.generalProducts || [];
  const ticketProducts = setupData.ticketProducts || [];
  const raffleProducts = setupData.raffleProducts || [];

  // ==================== 1. 상품 목록 조회 (페이지네이션) ====================
  group('Product: 목록 조회 - 페이지 1 (20개)', () => {
    const listRes = http.get(`${BASE_URL}/product?page=0&size=20&sort=id,DESC`, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(listRes, {
      'status is 200': (r) => r.status === 200,
      'has content': (r) => r.json('content') !== null,
      'has pagination': (r) => r.json('number') === 0,
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
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(0.3);

  // ==================== 3. 카테고리별 필터링 ====================
  group('Product: 카테고리별 필터 - GENERAL', () => {
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

  group('Product: 카테고리별 필터 - TICKET', () => {
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

  group('Product: 카테고리별 필터 - RAFFLE', () => {
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

  // ==================== 4. 상품 상세 조회 ====================
  if (generalProducts.length > 0) {
    group('Product: 상세 조회 - GENERAL 상품', () => {
      const selectedProduct =
        generalProducts[__ITER % generalProducts.length];

      const detailRes = http.get(`${BASE_URL}/product/${selectedProduct.id}`, {
        headers: { 'Content-Type': 'application/json' },
      });

      check(detailRes, {
        'status is 200': (r) => r.status === 200,
        'has id': (r) => r.json('id') !== null,
        'has name': (r) => r.json('name') !== null,
        'has price': (r) => r.json('price') > 0,
        'response time < 300ms': (r) => r.timings.duration < 300,
      });
    });

    sleep(0.2);
  }

  if (ticketProducts.length > 0) {
    group('Product: 상세 조회 - TICKET 상품', () => {
      const selectedProduct =
        ticketProducts[__ITER % ticketProducts.length];

      const detailRes = http.get(`${BASE_URL}/product/${selectedProduct.id}`, {
        headers: { 'Content-Type': 'application/json' },
      });

      check(detailRes, {
        'status is 200': (r) => r.status === 200,
        'has category': (r) => r.json('category') === 'TICKET',
        'response time < 300ms': (r) => r.timings.duration < 300,
      });
    });

    sleep(0.2);
  }

  if (raffleProducts.length > 0) {
    group('Product: 상세 조회 - RAFFLE 상품', () => {
      const selectedProduct =
        raffleProducts[__ITER % raffleProducts.length];

      const detailRes = http.get(`${BASE_URL}/product/${selectedProduct.id}`, {
        headers: { 'Content-Type': 'application/json' },
      });

      check(detailRes, {
        'status is 200': (r) => r.status === 200,
        'has category': (r) => r.json('category') === 'RAFFLE',
        'response time < 300ms': (r) => r.timings.duration < 300,
      });
    });

    sleep(0.2);
  }

  // ==================== 5. 정렬 테스트 ====================
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

  // ==================== 6. 상태별 필터 ====================
  group('Product: 상태별 필터 - 재고 있음', () => {
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

  // ==================== 7. 범위 쿼리 (여러 페이지) ====================
  group('Product: 페이지네이션 - 페이지 2', () => {
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

  group('Product: 페이지네이션 - 페이지 3', () => {
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
  console.log('✅ Product 성능 테스트 완료');
  console.log(`   테스트된 상품: ${setupData.products.length}개`);
}
