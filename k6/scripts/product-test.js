import http from 'k6/http';
import { check, group, sleep } from 'k6';

// Product 도메인 부하 테스트
// 실제 API: GET /v1/product (List with pagination), GET /v1/product/{id} (Detail), GET /v1/product/search (Search)
export const options = {
  stages: [
    { duration: '30s', target: 20 },   // 램프업: 20 VU
    { duration: '1m', target: 50 },    // 증가: 50 VU
    { duration: '1m', target: 100 },   // 최대: 100 VU
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
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
  // 첫 요청에 로그인
  if (!authToken) {
    login();
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${authToken}`,
  };

  group('1. 상품 목록 조회 (페이지네이션)', () => {
    const listRes = http.get(`${BASE_URL}/product?page=0&size=20`, { headers });

    check(listRes, {
      'list status is 200': (r) => r.status === 200,
      'list has content': (r) => r.json('content') !== null,
      'list response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(1);

  group('2. 상품 상세 조회', () => {
    const detailRes = http.get(`${BASE_URL}/product/${TEST_PRODUCT_ID}`, { headers });

    check(detailRes, {
      'detail status is 200 or 404': (r) => r.status === 200 || r.status === 404,
      'detail response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(1);

  group('3. 상품 검색 (조건 검색)', () => {
    const searchRes = http.get(`${BASE_URL}/product/search?name=&page=0&size=20`, { headers });

    check(searchRes, {
      'search status is 200': (r) => r.status === 200,
      'search has content': (r) => r.json('content') !== null,
      'search response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(1);

  group('4. 페이지네이션 다양한 크기', () => {
    const pageRes = http.get(`${BASE_URL}/product?page=${Math.floor(Math.random() * 5)}&size=10`, { headers });

    check(pageRes, {
      'pagination status is 200': (r) => r.status === 200,
      'pagination response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(2);
}
