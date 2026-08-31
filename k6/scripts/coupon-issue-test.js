import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter, Rate } from 'k6/metrics';

/**
 * #1 한정 수량 쿠폰 발급 - 라운드별 재측정용 드라이버
 *
 * 시나리오: 한 쿠폰(수량 Q)에 다수 사용자가 동시에 발급 요청.
 * 어느 라운드(db-lock / lua-then-lock / kafka)를 재는지는 서버 설정이 정하고,
 * 이 스크립트는 라운드에 관계없이 같은 부하를 만든다. 그래야 라운드 간 비교가 성립한다.
 *
 * 측정: 발급 응답시간 p95/p99 + 결과 분포 + 무관한 기능의 성공률(bystander).
 *       초과발급·미달 검증은 런북 SQL(backend/docs/measurements/runbook_assert.sql).
 *
 * ⚠️ 접수 지연과 확정 지연은 다른 값이다. issue_latency 는 서버 응답까지만 잰다.
 *    비동기 라운드에서 확정까지 걸린 시간은 confirm_latency 로 따로 잰다.
 *
 * 전제: COUPON_ID = 활성 쿠폰. 회차 전 초기화와 부하 유저 생성은 scripts/measure-round.sh 가 한다.
 *
 * 실행: ./scripts/measure-round.sh <전략명> <회차>
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const COUPON_ID = __ENV.COUPON_ID; // 필수
const USERS = parseInt(__ENV.USERS || '100000', 10);
const VUS = parseInt(__ENV.VUS || '500', 10);
const ITERATIONS = parseInt(__ENV.ITERATIONS || '100000', 10);
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';

// 실제로 요청을 넣는 서로 다른 사용자 수. 요청 수보다 적게 잡아 뒤쪽 반복이 앞쪽 사용자로 되돌아온다
// 전원이 서로 다른 사용자면 한 사람도 두 번 요청하지 않아 중복 방어 경로가 한 번도 실행되지 않는다
// 기본값 90% 는 나머지 10% 를 중복 요청으로 만든다
const DISTINCT_USERS = parseInt(__ENV.DISTINCT_USERS || String(Math.floor(USERS * 0.9)), 10);

// 라운드마다 기대치가 다르므로 threshold 를 환경변수로 주입한다
// 미달이 곧 회차 실패는 아니다. 판정은 런북 단언이 하고 여기 값은 관측값으로 기록한다
const P95_MS = parseInt(__ENV.P95_MS || '200', 10);
const P99_MS = parseInt(__ENV.P99_MS || '300', 10);

// 부하가 끝나도 bystander 시나리오는 이 시간까지 계속 돈다
// 관측한 부하 소요 시간보다 조금 크게 잡는다
const MAX_DURATION = __ENV.MAX_DURATION || '20m';

// 비동기 라운드에서 확정 결과를 몇 번까지 물어볼지
const POLL_TRIES = parseInt(__ENV.POLL_TRIES || '5', 10);
const POLL_INTERVAL_S = parseFloat(__ENV.POLL_INTERVAL_S || '1');

const STRATEGY = __ENV.STRATEGY || 'unknown';
const ROUND = __ENV.ROUND || '0';

const issueLatency = new Trend('issue_latency', true);   // 발급 요청의 서버 응답시간(ms)
const confirmLatency = new Trend('confirm_latency', true); // 접수부터 확정 관측까지(ms). 비동기 라운드 전용
const loginLatency = new Trend('login_latency', true);   // 로그인이 측정을 지배하는지 보려는 것
const issueSuccess = new Counter('issue_success');       // 확정 성공(SUCCESS)
const issueSoldout = new Counter('issue_soldout');       // 진짜 품절(COUPON_OUT_OF_STOCK)
const issueDup = new Counter('issue_dup');               // 이미 발급(COUPON_ALREADY_ISSUED)
const issueContention = new Counter('issue_contention'); // 경합 실패(COUPON_ISSUE_CONTENTION) = 거짓 품절
const issueMismatch = new Counter('issue_mismatch');     // Redis/DB 재고 불일치(COUPON_STORE_MISMATCH)
const issueOther = new Counter('issue_other');           // 기타
const issuePending = new Counter('issue_pending');       // 확정 미도달(WAITING/UNKNOWN). 실패로 세지 않는다
const issueTimeout = new Counter('issue_timeout');       // 응답 자체가 오지 않음(status 0)
const loginFail = new Counter('login_fail');             // 부하 유저 로그인 실패. 측정 준비 결함이다

// 장애 전파 여부는 쿠폰 지표로 알 수 없다
// 쿠폰과 무관한 조회를 부하 내내 저부하로 때려 그 성공률이 무너지는지를 본다
// S0 에서 무너지고 S1 에서 유지되면 "커넥션 고갈이 다른 기능까지 끌고 내려갔다" 가 성립한다
// 부하 시작부터 그 반복까지의 경과 시간. 최댓값이 곧 발급 부하의 실제 소요다
// 벽시계 시간은 bystander 가 MAX_DURATION 까지 도는 만큼 부풀려져 처리량 계산을 틀리게 한다
const rushElapsed = new Trend('rush_elapsed_s');
const bystanderOk = new Rate('bystander_ok');
const bystanderLat = new Trend('bystander_latency', true);

export const options = {
  scenarios: {
    coupon_rush: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: MAX_DURATION,
    },
    bystander: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: MAX_DURATION,
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'browseProducts',
    },
  },
  thresholds: {
    issue_latency: [`p(95)<${P95_MS}`, `p(99)<${P99_MS}`],
  },
  tags: { strategy: STRATEGY, round: ROUND },
};

export function setup() {
  if (!COUPON_ID) throw new Error('COUPON_ID 환경변수가 필요합니다. (예: -e COUPON_ID=1)');
  // 부하 유저는 runbook_seed_users.sql 이 미리 심는다
  // 회원가입 API 로 10만 명을 만들면 요청마다 BCrypt 비용이 붙어 회차 준비가 시간 단위가 된다
  const probe = http.post(`${BASE_URL}/auth/login`,
    JSON.stringify({ email: 'lt_1@test.com', password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (probe.status !== 200) {
    throw new Error(`부하 유저 로그인 실패(status=${probe.status}). runbook_seed_users.sql 을 먼저 실행하라`);
  }
  console.log(`✅ 준비 완료. COUPON_ID=${COUPON_ID} 전략=${STRATEGY} 회차=${ROUND} 참여 사용자=${DISTINCT_USERS}`);
  return { couponId: COUPON_ID, startedAt: Date.now() };
}

// VU 마다 마지막으로 로그인한 사용자의 토큰만 들고 있는다
// 반복마다 사용자가 바뀌므로 캐시 적중은 중복 요청 구간에서만 생긴다
let cachedToken = null;
let cachedUser = null;

function login(email) {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  loginLatency.add(res.timings.duration);
  if (res.status !== 200) {
    loginFail.add(1);
    return null;
  }
  return res.json('accessToken');
}

export default function (data) {
  // 사용자를 __VU 로 고르면 참여 사용자가 VU 수(500명)로 묶여
  // 재고 1만을 채우기 전에 전원이 중복 요청으로 바뀐다
  // 전체 반복 순번으로 골라야 요청 수만큼 서로 다른 사용자가 참여한다
  rushElapsed.add((Date.now() - data.startedAt) / 1000);
  const userNum = (exec.scenario.iterationInTest % DISTINCT_USERS) + 1;
  const email = `lt_${userNum}@test.com`;

  if (cachedUser !== email) {
    cachedToken = login(email);
    cachedUser = email;
  }
  if (!cachedToken) { issueOther.add(1); return; }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${cachedToken}`,
  };

  const startedAt = Date.now();
  // 라운드에 관계없이 이 경로 하나만 때린다. 어떤 방식으로 처리할지는 서버 설정이 정한다
  const res = http.post(`${BASE_URL}/coupon/issue/${data.couponId}`, null,
    { headers, tags: { name: 'issue' } });
  issueLatency.add(res.timings.duration);

  // status 0 은 응답이 아예 오지 않은 것이다. 실패 분류에 섞으면 타임아웃률을 잃는다
  if (res.status === 0) { issueTimeout.add(1); return; }

  // 동기 라운드(S0·S1)는 여기서 확정까지 끝난다
  if (res.status === 200 || res.status === 201) { issueSuccess.add(1); return; }

  if (res.status !== 202) {
    // 게이트가 접수 단계에서 자른 요청이다. 브로커에는 실리지 않았다
    classifyFailure(res);
    return;
  }

  // 비동기 라운드(S2)만 여기로 온다. 확정은 컨슈머가 하므로 상태를 물어 최종 사유를 얻는다
  // 이 조회도 톰캣 스레드를 쓰므로 부하에 포함된다
  const requestId = res.json('requestId');
  for (let i = 0; i < POLL_TRIES; i++) {
    sleep(POLL_INTERVAL_S);
    const st = http.get(`${BASE_URL}/coupon/issue-async/${requestId}/status`,
      { headers, tags: { name: 'status' } });
    const status = st.status === 200 ? st.json('status') : 'UNKNOWN';
    if (status === 'SUCCESS') {
      issueSuccess.add(1);
      confirmLatency.add(Date.now() - startedAt);
      return;
    }
    if (status.startsWith('FAILED:')) {
      classify(status.substring('FAILED:'.length));
      confirmLatency.add(Date.now() - startedAt);
      return;
    }
  }
  // WAITING/UNKNOWN 은 미확정이다. 실패로 세면 폴링 창의 길이가 곧 실패율이 된다
  issuePending.add(1);
}

// 쿠폰과 무관한 조회. 인증이 필요 없는 경로라 로그인 부하가 섞이지 않는다
export function browseProducts() {
  const res = http.get(`${BASE_URL}/product?page=0&size=20`, { tags: { name: 'bystander' } });
  bystanderOk.add(res.status === 200);
  bystanderLat.add(res.timings.duration);
}

// 접수 거절(409) 응답을 분류한다.
// ErrorResponse 는 code 필드에 ErrorCode enum 이름을 그대로 실어 보낸다(ErrorResponse.from).
// 메시지 본문 부분일치로 나누면 문구를 고칠 때마다 집계가 조용히 틀어지므로 code 를 우선 본다.
//
// 실패를 네 갈래로 나누는 것이 이 스크립트의 목적이다.
// - OUT_OF_STOCK  : 재고가 실제로 0 (정상 동작)
// - ISSUE_CONTENTION : 재고는 남았는데 경합으로 실패 = "거짓 품절". 이 값이 라운드 간 개선 지표다
// - ALREADY_ISSUED : 중복 요청. Lua 가 중복을 재고보다 먼저 보므로 품절 이후에도 여기로 잡힌다
// - STORE_MISMATCH : Redis 는 통과했는데 DB 수량이 0. 두 저장소가 어긋난 상태로 조사 대상이다
function classifyFailure(res) {
  const body = res.body || '';
  let code = null;
  try {
    code = res.json('code');
  } catch (e) {
    code = null; // JSON 이 아닌 응답(게이트웨이 오류 등)
  }

  if (classify(code)) {
    return;
  }

  // code 가 없는 응답을 위한 폴백. 중복을 품절보다 먼저 본다 -
  // 품절 조건의 '수량' 이 중복 메시지와 겹칠 여지가 있어 순서를 뒤집으면 중복이 품절로 흡수된다
  if (body.includes('ALREADY') || body.includes('이미') || body.includes('중복') || body.includes('DUPLICATE')) {
    issueDup.add(1);
  } else if (body.includes('SOLD_OUT') || body.includes('소진') || body.includes('수량')) {
    issueSoldout.add(1);
  } else {
    issueOther.add(1);
  }
}

// ErrorCode 이름으로 실패 사유를 나눈다. 분류했으면 true 를 반환한다
// 이 분리가 "거짓 품절이 사라졌다"를 수치로 지지하는 근거다 - contention 카운터가 0 이어야 한다
function classify(code) {
  switch (code) {
    case 'COUPON_OUT_OF_STOCK':     issueSoldout.add(1); return true;
    case 'COUPON_ALREADY_ISSUED':   issueDup.add(1); return true;
    case 'COUPON_ISSUE_CONTENTION': issueContention.add(1); return true;
    case 'COUPON_STORE_MISMATCH':   issueMismatch.add(1); return true;
    default:                        return false;
  }
}

export function teardown() {
  console.log(`✅ 전략=${STRATEGY} 회차=${ROUND} 측정 완료.`);
  console.log('   발급 수·중복·잔여 수량 판정은 runbook_assert.sql 이 한다.');
  console.log('   실패 내역은 issue_soldout(진짜 품절) / issue_contention(거짓 품절) /');
  console.log('   issue_dup(중복) / issue_mismatch(저장소 불일치) 로 나눠 읽는다.');
  console.log('   issue_pending 은 폴링 창 안에 확정이 안 끝난 건이다. 실패가 아니다.');
  console.log('   bystander_ok 가 무너졌으면 쿠폰 부하가 무관한 기능까지 끌고 내려간 것이다.');
}
