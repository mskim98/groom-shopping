import http from 'k6/http';
import { check, group, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },   // 10개의 VU로 램프업
    { duration: '1m', target: 30 },    // 30개 VU로 증가
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

// 테스트 데이터
const testUsers = [
  { email: 'test1@example.com', password: 'password123' },
  { email: 'test2@example.com', password: 'password123' },
  { email: 'test3@example.com', password: 'password123' },
];

let authToken = '';

export default function () {
  const user = testUsers[Math.floor(Math.random() * testUsers.length)];

  group('1. 회원가입 시도', () => {
    const signupPayload = JSON.stringify({
      email: `user_${__VU}_${__ITER}@test.com`,
      password: 'password123',
      nickname: `user_${__VU}`,
    });

    const signupRes = http.post(`${BASE_URL}/auth/signup`, signupPayload, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(signupRes, {
      'signup status 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
  });

  sleep(1);

  group('2. 로그인', () => {
    const loginPayload = JSON.stringify({
      email: user.email,
      password: user.password,
    });

    const loginRes = http.post(`${BASE_URL}/auth/login`, loginPayload, {
      headers: { 'Content-Type': 'application/json' },
    });

    check(loginRes, {
      'login status is 200': (r) => r.status === 200,
      'has access token': (r) => r.json('data.accessToken') !== undefined,
    });

    // 응답에서 토큰 추출
    if (loginRes.status === 200) {
      authToken = loginRes.json('data.accessToken');
    }
  });

  sleep(1);

  group('3. 상품 목록 조회', () => {
    const headers = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${authToken}`,
    };

    const productRes = http.get(`${BASE_URL}/product/list`, { headers });

    check(productRes, {
      'product list status 200': (r) => r.status === 200,
      'has products': (r) => r.json('data.length') > 0,
      'response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(1);

  group('4. 상품 상세 조회', () => {
    const headers = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${authToken}`,
    };

    const detailRes = http.get(`${BASE_URL}/product/1`, { headers });

    check(detailRes, {
      'product detail status 200 or 404': (r) => r.status === 200 || r.status === 404,
    });
  });

  sleep(1);

  group('5. 주문 조회', () => {
    const headers = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${authToken}`,
    };

    const orderRes = http.get(`${BASE_URL}/order/list`, { headers });

    check(orderRes, {
      'order list status 200': (r) => r.status === 200,
      'response time < 1000ms': (r) => r.timings.duration < 1000,
    });
  });

  sleep(2);
}
