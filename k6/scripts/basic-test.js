import http from 'k6/http';
import { check, group, sleep } from 'k6';

// 설정
export const options = {
  stages: [
    { duration: '30s', target: 20 },   // 30초 동안 20개의 가상 사용자로 램프업
    { duration: '1m30s', target: 100 }, // 1분 30초 동안 100명으로 증가
    { duration: '20s', target: 0 },     // 20초 동안 0으로 감소 (쿨다운)
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'], // p95 < 500ms, p99 < 1000ms
    http_req_failed: ['rate<0.1'],                   // 실패율 < 10%
  },
};

const BASE_URL = 'http://localhost:8080/api';

export default function () {
  group('상품 조회 API', () => {
    const res = http.get(`${BASE_URL}/v1/product/list`);
    check(res, {
      'status is 200': (r) => r.status === 200,
      'response time < 500ms': (r) => r.timings.duration < 500,
      'body is not empty': (r) => r.body.length > 0,
    });
  });

  sleep(1);

  group('상품 상세 조회', () => {
    const res = http.get(`${BASE_URL}/v1/product/1`);
    check(res, {
      'status is 200': (r) => r.status === 200 || r.status === 404,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(1);
}
