import http from 'k6/http';
import { check, sleep } from 'k6';

// 스파이크 테스트: 갑작스러운 트래픽 증가
export const options = {
  stages: [
    { duration: '10s', target: 10 },    // 정상 트래픽 10 VU
    { duration: '1s', target: 100 },    // 갑작스럽게 100 VU로 증가 (스파이크)
    { duration: '10s', target: 100 },   // 높은 트래픽 유지
    { duration: '5s', target: 0 },      // 급감
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.2'],
  },
};

const BASE_URL = 'http://localhost:8080/api/v1';

export default function () {
  // 상품 목록 조회
  const res = http.get(`${BASE_URL}/product/list`);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 2000ms': (r) => r.timings.duration < 2000,
  });

  sleep(0.5);
}
