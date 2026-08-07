import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';

/**
 * #1 선착순 쿠폰 발급 동시성 측정 (Redisson 분산 락 + Lua "after")
 *
 * 시나리오: 한 쿠폰(수량 Q)에 다수 사용자가 동시에 발급 요청 → Redisson 락 경합.
 * 측정: 발급(POST /coupon/issue/{id}) p95/p99, 초과발급 0 검증(런북 SQL: coupon_issue 행 수 == min(Q, 성공자)).
 *
 * ⚠️ A/B의 "before"(비관적 락)는 코드에서 제거됨 → 이 스크립트는 "after" 절대값 측정용.
 *    350ms→85ms 비교를 내려면 비관적 락 경로를 feature flag 로 복원해 동일 스크립트로 재측정해야 함(런북 참조).
 *
 * 전제: COUPON_ID = 활성 쿠폰. 측정 전 quantity 설정 + coupon_issue 정리(런북).
 *       부하 유저 lt_1..N (stock 테스트의 setup 으로 이미 생성됨, 비번 loadtest123!).
 *
 * 실행 예:
 *   k6 run -e COUPON_ID=1 -e USERS=200 -e VUS=200 -e ITERATIONS=5000 \
 *          --summary-export=backend/docs/measurements/coupon.json k6/scripts/coupon-issue-test.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const COUPON_ID = __ENV.COUPON_ID; // 필수
const USERS = parseInt(__ENV.USERS || '200', 10);
const VUS = parseInt(__ENV.VUS || '200', 10);
const ITERATIONS = parseInt(__ENV.ITERATIONS || '5000', 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';

const issueLatency = new Trend('issue_latency', true); // 발급 응답시간(ms)
const issueSuccess = new Counter('issue_success');     // 201 발급 성공
const issueSoldout = new Counter('issue_soldout');     // 수량 소진(품절)
const issueDup = new Counter('issue_dup');             // 이미 발급(중복)
const issueOther = new Counter('issue_other');         // 기타

export const options = {
  scenarios: {
    coupon_rush: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: '10m',
    },
  },
  thresholds: {
    // 이력서 가정치(참고): p99 85ms. 실측 후 갱신.
    issue_latency: ['p(95)<200', 'p(99)<300'],
  },
};

let cachedToken = null;
let cachedUser = null;

export function setup() {
  if (!COUPON_ID) throw new Error('COUPON_ID 환경변수가 필요합니다. (예: -e COUPON_ID=1)');
  // 부하 유저 보강(이미 있으면 무시)
  for (let i = 1; i <= USERS; i++) {
    http.post(`${BASE_URL}/auth/signup`, JSON.stringify({
      email: `lt_${i}@test.com`, password: PASSWORD, name: `lt_user_${i}`,
    }), { headers: { 'Content-Type': 'application/json' } });
  }
  console.log(`✅ 부하 유저 준비. COUPON_ID=${COUPON_ID}`);
  return { couponId: COUPON_ID };
}

function login(email) {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  return res.status === 200 ? res.json('accessToken') : null;
}

export default function (data) {
  const userNum = ((__VU - 1) % USERS) + 1;
  const email = `lt_${userNum}@test.com`;
  if (!cachedToken || cachedUser !== email) {
    cachedToken = login(email);
    cachedUser = email;
  }
  if (!cachedToken) { issueOther.add(1); return; }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${cachedToken}`,
    'Request-Date': new Date().toUTCString(), // 서버 시각 ±1분 검증 통과용(RFC1123)
  };

  const res = http.post(`${BASE_URL}/coupon/issue/${data.couponId}`, null, { headers });
  issueLatency.add(res.timings.duration);
  const body = res.body || '';
  if (res.status === 201) {
    issueSuccess.add(1);
  } else if (body.includes('SOLD_OUT') || body.includes('소진') || body.includes('수량')) {
    issueSoldout.add(1);
  } else if (body.includes('ALREADY') || body.includes('이미') || body.includes('중복') || body.includes('DUPLICATE')) {
    issueDup.add(1);
  } else {
    issueOther.add(1);
  }
}

export function teardown() {
  console.log('✅ 쿠폰 발급 측정 완료. 초과발급은 런북 SQL 로 (coupon_issue 행 수 <= quantity) 검증.');
}
