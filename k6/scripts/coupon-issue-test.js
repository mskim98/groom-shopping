import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// 부하 유저의 실제 id 목록. measure-round.sh 가 회차 시작 전에 DB 에서 뽑아 둔다
// 순번에 기준값을 더하는 방식은 못 쓴다 - 다른 테스트가 만든 계정이 사이에 끼어 id 가 연속이 아니다
// SharedArray 라 VU 수와 무관하게 메모리에 한 벌만 올라간다
const LT_USER_IDS = new SharedArray('lt_user_ids', function () {
  return open('./lt_user_ids.txt').split('\n').filter(function (line) {
    return line.trim().length > 0;
  });
});

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

// 부하 모델 선택 - 기본값은 기존 shared-iterations 라 앞선 회차가 그대로 재현된다
//
// shared  : 고정 VU 가 응답을 받아야 다음 요청을 보낸다(닫힌 모델).
//           요청 속도가 서버가 아니라 VU 수 ÷ 반복 1회 소요로 정해지므로,
//           반복 안에 폴링 sleep 이 있으면 그 값이 곧 도착률의 상한이 된다
//           (실측: 500 VU ÷ 1초 폴링 = 초당 500건에서 멈춤)
// arrival : 도착률을 직접 지정한다(열린 모델). 서버가 못 받으면 k6 가
//           dropped_iterations 로 보고하므로 포화 지점이 드러난다
const RUSH_EXECUTOR = __ENV.RUSH_EXECUTOR || 'shared';
const ARRIVAL_RATE = parseInt(__ENV.ARRIVAL_RATE || '5000', 10);
const ARRIVAL_DURATION = __ENV.ARRIVAL_DURATION || '20s';
// 열린 모델은 응답이 늦어지면 VU 를 더 꺼내 쓴다. 상한이 낮으면 도착률을 못 채우고
// 그것이 서버 포화와 구별되지 않으므로 넉넉히 잡는다
const PRE_VUS = parseInt(__ENV.PRE_VUS || '2000', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '8000', 10);

// spike : 실제 이벤트 모양(오픈 직후 급등 - 유지 - 감쇠). 총량은 약 10만 건으로 맞춘다
// 등간격 유입은 총량과 평균 속도는 재현하지만 모양이 평평해, 순간 몰림에서만 나는 거동을 못 본다
const SPIKE_PEAK = parseInt(__ENV.SPIKE_PEAK || '5000', 10);
const spikeStages = [
  { duration: '1s', target: SPIKE_PEAK },                        // 오픈 - 급등
  { duration: '8s', target: SPIKE_PEAK },                        // 최고치 유지
  { duration: '12s', target: Math.round(SPIKE_PEAK / 5) },       // 재고 소진 뒤 감쇠
  { duration: '22s', target: Math.round(SPIKE_PEAK / 5) },       // 잔여 유입
];

const rushScenario = RUSH_EXECUTOR === 'spike'
  ? {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: spikeStages,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    }
  : RUSH_EXECUTOR === 'arrival'
  ? {
      executor: 'constant-arrival-rate',
      rate: ARRIVAL_RATE,
      timeUnit: '1s',
      duration: ARRIVAL_DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    }
  : {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: MAX_DURATION,
    };

const issueLatency = new Trend('issue_latency', true);   // 발급 요청의 서버 응답시간(ms)
const confirmLatency = new Trend('confirm_latency', true); // 접수부터 확정 관측까지(ms). 비동기 라운드 전용
// 인증은 부하에서 뺀다. 요청마다 로그인하면 사용자 조회가 요청당 2회(로그인 + 인증 필터) 붙어
// 커넥션 풀 포화가 발급 경로 때문인지 인증 때문인지 가릴 수 없다 (실측: 10만 요청에 20만 회)
const issueSuccess = new Counter('issue_success');       // 확정 성공(SUCCESS)
const issueSoldout = new Counter('issue_soldout');       // 재고가 실제로 0 (COUPON_OUT_OF_STOCK)
const issueDup = new Counter('issue_dup');               // 이미 발급(COUPON_ALREADY_ISSUED)
const issueContention = new Counter('issue_contention'); // 재고가 남았는데 경합으로 처리하지 못한 건
const issueMismatch = new Counter('issue_mismatch');     // Redis/DB 재고 불일치(COUPON_STORE_MISMATCH)
const issueOther = new Counter('issue_other');           // 기타
const issuePending = new Counter('issue_pending');       // 확정 미도달(WAITING/UNKNOWN). 실패로 세지 않는다
const issueTimeout = new Counter('issue_timeout');       // 응답 자체가 오지 않음(status 0)
const authFail = new Counter('auth_fail');               // 인증 우회가 안 먹은 경우. 측정 준비 결함이다

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
    coupon_rush: rushScenario,
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
  if (LT_USER_IDS.length < DISTINCT_USERS) {
    throw new Error(`부하 유저가 ${LT_USER_IDS.length} 명뿐이다. 참여 예정 ${DISTINCT_USERS} 명`);
  }
  // 우회 필터가 켜져 있는지 먼저 끊는다. 안 켜져 있으면 전건 401 로 끝나 회차 하나를 버린다
  const probe = http.get(`${BASE_URL}/coupon/me`, { headers: { 'X-Lt-User-Id': LT_USER_IDS[0] } });
  if (probe.status === 401 || probe.status === 403) {
    throw new Error('인증 우회가 꺼져 있다. loadtest.auth-bypass.enabled=true 로 서버를 띄워라');
  }
  console.log(`✅ 준비 완료. COUPON_ID=${COUPON_ID} 전략=${STRATEGY} 회차=${ROUND} 참여 사용자=${DISTINCT_USERS}`);
  return { couponId: COUPON_ID, startedAt: Date.now() };
}

export default function (data) {
  // 사용자를 __VU 로 고르면 참여 사용자가 VU 수(500명)로 묶여
  // 재고 1만을 채우기 전에 전원이 중복 요청으로 바뀐다
  // 전체 반복 순번으로 골라야 요청 수만큼 서로 다른 사용자가 참여한다
  rushElapsed.add((Date.now() - data.startedAt) / 1000);
  const userIndex = exec.scenario.iterationInTest % DISTINCT_USERS;

  // 사용자 식별을 헤더로만 넘긴다. 로그인 왕복도, 인증 필터의 DB 조회도 없다
  const headers = {
    'Content-Type': 'application/json',
    'X-Lt-User-Id': LT_USER_IDS[userIndex],
  };

  const startedAt = Date.now();
  // 라운드에 관계없이 이 경로 하나만 때린다. 어떤 방식으로 처리할지는 서버 설정이 정한다
  const res = http.post(`${BASE_URL}/coupon/issue/${data.couponId}`, null,
    { headers, tags: { name: 'issue' } });
  issueLatency.add(res.timings.duration);

  // status 0 은 응답이 아예 오지 않은 것이다. 실패 분류에 섞으면 타임아웃률을 잃는다
  if (res.status === 0) { issueTimeout.add(1); return; }
  // 401/403 은 우회가 안 먹은 것이다. 실패 분류에 섞으면 측정 결함이 품절로 보인다
  if (res.status === 401 || res.status === 403) { authFail.add(1); return; }

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
// - ISSUE_CONTENTION : 재고는 남아 있는데 잠금을 못 얻어 실패. 이 값이 라운드 간 개선 지표다
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
// 이 분리가 있어야 "재고가 남았는데 실패한 건이 사라졌다" 를 수치로 말할 수 있다
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
  console.log('   실패 내역은 issue_soldout(재고 소진) / issue_contention(재고는 남았으나 경합) /');
  console.log('   issue_dup(중복) / issue_mismatch(저장소 불일치) 로 나눠 읽는다.');
  console.log('   issue_pending 은 폴링 창 안에 확정이 안 끝난 건이다. 실패가 아니다.');
  console.log('   bystander_ok 가 무너졌으면 쿠폰 부하가 무관한 기능까지 끌고 내려간 것이다.');
}
