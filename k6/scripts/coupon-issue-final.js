import http from 'k6/http';
import exec from 'k6/execution';
import {Counter, Trend} from 'k6/metrics';
import {sleep} from 'k6';

/**
 * 쿠폰 발급 최종 측정 - 수량보다 많은 요청을 일정한 도착률로 보내고, 발급 요청 접수부터 상태 조회가 SUCCESS 를 돌려줄 때까지의 시간을 잰다
 * 반복 하나 = 사용자 하나의 발급 요청 1건(쿠폰당 사용자당 1장 제한이라 사용자는 반복마다 다르다) → 202 면 상태가 SUCCESS · FAILED 가 될 때까지 POLL_MS 간격으로 조회
 * ISSUE_PATH=issue 면 POST /coupon/issue/{id}(200 이면 그 응답 시간이 최종 응답까지의 시간, 202 면 위와 같이 조회), 기본 issue-async 는 POST /coupon/issue-async/{id}
 * 환경 변수: COUPON_ID(필수) · ISSUE_PATH(issue-async) · RATE(500, 초당 요청) · DURATION_S(6) · MAX_VUS(3000) · POLL_MS(100) · POLL_MAX_S(30) · BASE_URL · SUMMARY_FILE · LT_PASSWORD
 * 사용자 수 = RATE × DURATION_S (seed_1 … seed_N@test.com 로그인)
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost/api/v1';
const COUPON_ID = __ENV.COUPON_ID;
const RATE = parseInt(__ENV.RATE || '500', 10);
const DURATION_S = parseInt(__ENV.DURATION_S || '6', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '3000', 10);
const POLL_MS = parseInt(__ENV.POLL_MS || '100', 10);
const POLL_MAX_S = parseInt(__ENV.POLL_MAX_S || '30', 10);
const ISSUE_PATH = __ENV.ISSUE_PATH || 'issue-async';
const PASSWORD = __ENV.LT_PASSWORD || 'loadtest123!';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'coupon-summary.json';
// AUTH=bypass: 로그인 없이 X-Lt-User-Id 헤더(반복 번호 + 1 = 시드 사용자 id)로 부른다 - 앱이 LOADTEST_AUTH_BYPASS_ENABLED=true 일 때만 통한다(r129 Redis 정지 우회 실행)
const AUTH = __ENV.AUTH || 'token';
const REQUESTS = RATE * DURATION_S;
// CONN_REUSE=0 이면 요청마다 새 연결을 연다(k6 noConnectionReuse) - 기본(켬)은 사용자(가상 사용자)마다 연결 하나를 열어 두고 다시 쓴다
// r131: 사용자 3,000명이 연결 3,000개를 쥐면 Docker Desktop 의 호스트 포트 전달기가 천 건쯤에서 nginx 로 가는 연결을 거절했다 - 그 원인을 가리는 스위치
const CONN_REUSE = (__ENV.CONN_REUSE || '1') !== '0';

const accept = new Trend('coupon_accept_ms', true);
const completion = new Trend('coupon_completion_ms', true);
// 최종 답까지의 시간을 결과별로 따로 모은다(r129) - issued 는 200 또는 상태 조회 SUCCESS, sold_out · duplicate 는 409, 그 밖(503 · 실패 · 시간 초과 · 기타)은 other
const answer = {};
for (const k of ['issued', 'sold_out', 'duplicate', 'other']) {
    answer[k] = new Trend('ans_' + k, true);
}
const result = {};
for (const k of ['accepted_202', 'sync_200', 'sold_out_409', 'duplicate', 'unavailable_503', 'other', 'success', 'failed', 'timeout', 'no_token']) {
    result[k] = new Counter('r_' + k);
}

export const options = {
    noConnectionReuse: !CONN_REUSE,
    setupTimeout: '600s',
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
    scenarios: {
        issue: {executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION_S + 's', preAllocatedVUs: MAX_VUS, maxVUs: MAX_VUS},
    },
};

function headers(token) {
    return AUTH === 'bypass' ? {'Content-Type': 'application/json', 'X-Lt-User-Id': String(token)} : {'Content-Type': 'application/json', Authorization: 'Bearer ' + token};
}

export function setup() {
    if (AUTH === 'bypass') {
        // 토큰 자리에 사용자 id 를 둔다(반복 i 의 사용자 = i + 1)
        console.log('setup: logins=0 missing_token=0 auth=bypass');
        return {tokens: Array.from({length: REQUESTS}, (_, i) => i + 1)};
    }
    const tokens = [];
    let missing = 0;
    const batch = 50;
    for (let from = 1; from <= REQUESTS; from += batch) {
        const reqs = [];
        for (let k = from; k < from + batch && k <= REQUESTS; k++) {
            reqs.push(['POST', BASE_URL + '/auth/login', JSON.stringify({email: 'seed_' + k + '@test.com', password: PASSWORD}),
                {headers: {'Content-Type': 'application/json'}, tags: {name: 'setup_login'}}]);
        }
        for (const r of http.batch(reqs)) {
            if (r.status !== 200) throw new Error('로그인 실패 status=' + r.status + ' body=' + r.body);
            const t = r.json('accessToken');
            if (!t) missing++;
            tokens.push(t);
        }
    }
    console.log('setup: logins=' + tokens.length + ' missing_token=' + missing);
    return {tokens};
}

export default function (data) {
    const token = data.tokens[exec.scenario.iterationInTest];
    // 토큰이 없는 반복은 요청을 보내지 못한다 - 조용히 버리지 않고 센다
    if (!token) {
        result.no_token.add(1);
        return;
    }
    const t0 = Date.now();
    const res = http.post(BASE_URL + '/coupon/' + ISSUE_PATH + '/' + COUPON_ID, null, {headers: headers(token), tags: {name: 'coupon_issue'}});
    accept.add(res.timings.duration);
    if (res.status === 202) {
        result.accepted_202.add(1);
        const requestId = res.json('requestId');
        const deadline = t0 + POLL_MAX_S * 1000;
        while (Date.now() < deadline) {
            const st = http.get(BASE_URL + '/coupon/issue-async/' + requestId + '/status', {headers: headers(token), tags: {name: 'coupon_status'}});
            const s = st.status === 200 ? st.json('status') : 'HTTP' + st.status;
            if (s === 'SUCCESS') {
                completion.add(Date.now() - t0);
                answer.issued.add(Date.now() - t0);
                result.success.add(1);
                return;
            }
            if (typeof s === 'string' && s.indexOf('FAILED') === 0) {
                result.failed.add(1);
                answer.other.add(Date.now() - t0);
                return;
            }
            sleep(POLL_MS / 1000);
        }
        result.timeout.add(1);
        answer.other.add(Date.now() - t0);
    } else if (res.status === 200) {
        result.sync_200.add(1);
        completion.add(res.timings.duration);
        answer.issued.add(res.timings.duration);
    } else if (res.status === 409) {
        // 품절과 중복은 응답 본문의 코드로 가른다
        const code = String(res.body).indexOf('ALREADY_ISSUED') >= 0 ? 'duplicate' : 'sold_out_409';
        result[code].add(1);
        answer[code === 'duplicate' ? 'duplicate' : 'sold_out'].add(res.timings.duration);
    } else if (res.status === 503) {
        result.unavailable_503.add(1);
        answer.other.add(res.timings.duration);
    } else {
        result.other.add(1);
        answer.other.add(res.timings.duration);
    }
}

export function handleSummary(data) {
    const m = data.metrics;
    const cnt = (n) => (m['r_' + n] ? m['r_' + n].values.count : 0);
    const out = {
        requests: m['coupon_accept_ms'].values.count,
        iterations: m.iterations ? m.iterations.values.count : 0,
        rate: RATE,
        duration_s: DURATION_S,
        connection_reuse: CONN_REUSE,
        accept_p95_ms: m['coupon_accept_ms'].values['p(95)'],
        completion_p50_ms: m['coupon_completion_ms'] ? m['coupon_completion_ms'].values['p(50)'] : null,
        completion_p95_ms: m['coupon_completion_ms'] ? m['coupon_completion_ms'].values['p(95)'] : null,
        completion_p99_ms: m['coupon_completion_ms'] ? m['coupon_completion_ms'].values['p(99)'] : null,
        completion_count: m['coupon_completion_ms'] ? m['coupon_completion_ms'].values.count : 0,
        results: {},
        dropped_iterations: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
        max_vus: m.vus_max ? m.vus_max.values.max : null,
    };
    for (const k of Object.keys(result)) out.results[k] = cnt(k);
    out.answer_ms = {};
    for (const k of Object.keys(answer)) {
        const v = m['ans_' + k] ? m['ans_' + k].values : null;
        out.answer_ms[k] = v ? {count: v.count, p50: v['p(50)'], p95: v['p(95)'], p99: v['p(99)'], max: v.max} : {count: 0, p50: null, p95: null, p99: null, max: null};
    }
    return {[SUMMARY_FILE]: JSON.stringify(out, null, 2)};
}
