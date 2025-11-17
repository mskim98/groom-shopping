# k6 부하 테스트 가이드

## 📊 개요

k6는 Go 기반의 현대적인 부하 테스트 도구입니다. JavaScript로 테스트 시나리오를 작성하고, 실시간으로 성능을 모니터링할 수 있습니다.

---

## 🚀 설치

### macOS
```bash
brew install k6
```

### Linux
```bash
sudo apt-get install k6
```

### Docker
```bash
docker pull grafana/k6:latest
```

### 설치 확인
```bash
k6 version
```

---

## 📝 테스트 스크립트

### 0️⃣ 통합 테스트 (integrated-test.js) ⭐ 추천

**목적**: Product, Order, Payment 전체 도메인 통합 테스트

**시나리오**:
1. 상품 목록 조회
2. 상품 상세 조회
3. 장바구니 조회
4. 장바구니에 상품 추가
5. 주문 생성
6. 주문 목록 조회
7. 결제 정보 조회
8. 결제 준비
9. 결제 승인
10. 결제 내역 조회

**실행**:
```bash
k6 run k6/scripts/integrated-test.js
```

---

### 1️⃣ 기본 테스트 (basic-test.js)

**목적**: 단순 GET 요청 부하 테스트

**시나리오**:
- 30초 동안 20명까지 램프업
- 1분 30초 동안 100명으로 증가
- 20초 동안 점진적 쿨다운

**테스트 항목**:
- 상품 목록 조회 (GET /api/v1/product/list)
- 상품 상세 조회 (GET /api/v1/product/1)

**실행**:
```bash
k6 run k6/scripts/basic-test.js
```

---

### 2️⃣ 사용자 흐름 테스트 (user-flow-test.js)

**목적**: 실제 사용자 시나리오 시뮬레이션

**시나리오**:
1. 회원가입 시도
2. 로그인 (토큰 획득)
3. 상품 목록 조회
4. 상품 상세 조회
5. 주문 목록 조회

**특징**:
- JWT 토큰 기반 인증
- 실제 사용자 흐름 모방
- 동적 사용자 생성

**실행**:
```bash
k6 run k6/scripts/user-flow-test.js
```

---

### 3️⃣ Product 도메인 테스트 (product-test.js)

**목적**: 상품 조회 API 성능 테스트

**시나리오**:
- 상품 목록 조회 (필터링)
- 상품 상세 조회
- 카테고리별 상품 조회
- 상품 검색
- 페이지네이션

**VU 설정**: 100 (최대)

**실행**:
```bash
k6 run k6/scripts/product-test.js
```

---

### 4️⃣ Order 도메인 테스트 (order-test.js)

**목적**: 주문 프로세스 성능 테스트

**시나리오**:
- 장바구니 조회
- 장바구니에 상품 추가
- 주문 생성
- 주문 목록 조회
- 주문 상세 조회
- 주문 취소

**VU 설정**: 50 (최대, 주문은 제한적)

**실행**:
```bash
k6 run k6/scripts/order-test.js
```

---

### 5️⃣ Payment 도메인 테스트 (payment-test.js)

**목적**: 결제 프로세스 성능 및 안정성 테스트

**시나리오**:
- 결제 정보 조회
- 결제 준비 (토스)
- 결제 승인
- 결제 내역 조회
- 결제 상세 조회
- 환불 요청

**VU 설정**: 30 (최대, 결제는 매우 신중하게)

**Threshold**:
- p95 < 2000ms
- p99 < 3000ms
- 실패율 < 0.05%

**실행**:
```bash
k6 run k6/scripts/payment-test.js
```

---

### 6️⃣ 스파이크 테스트 (spike-test.js)

**목적**: 갑작스러운 트래픽 증가 대응 테스트

**시나리오**:
- 정상 트래픽 (10 VU) 유지
- 갑작스럽게 100 VU로 증가 (스파이크)
- 높은 트래픽 유지
- 급감

**언제 사용**:
- 플래시 세일
- 공지사항 공개
- SNS 바이럴

**실행**:
```bash
k6 run k6/scripts/spike-test.js
```

---

## 🎯 테스트 옵션

### 시간 기반 테스트 (60초)
```bash
k6 run --duration 60s k6/scripts/basic-test.js
```

### 반복 기반 테스트 (100회 반복)
```bash
k6 run --iterations 100 k6/scripts/basic-test.js
```

### VU (Virtual Users) 지정
```bash
k6 run --vus 50 --duration 30s k6/scripts/basic-test.js
```

### 결과를 JSON 파일로 저장
```bash
k6 run --out json=results.json k6/scripts/basic-test.js
```

### Grafana로 실시간 모니터링
```bash
k6 run --out grafana k6/scripts/basic-test.js
```

---

## 📊 테스트 결과 해석

### 주요 메트릭

| 메트릭 | 설명 | 목표 |
|--------|------|------|
| **http_reqs** | 총 HTTP 요청 수 | 높을수록 좋음 |
| **http_req_duration** | 요청 응답 시간 | p95 < 500ms, p99 < 1000ms |
| **http_req_failed** | 실패한 요청 비율 | < 1% |
| **vus** | 활성 가상 사용자 수 | - |
| **iteration_duration** | 전체 반복 시간 | - |

### 예시 결과
```
     data_received..................: 50 MB   8.3 MB/s
     data_sent......................: 10 MB   1.7 MB/s
     http_req_blocked...............: avg=1.2ms    p(95)=5.3ms    p(99)=10.2ms
     http_req_connecting............: avg=0.8ms    p(95)=3.1ms    p(99)=7.2ms
     http_req_duration..............: avg=123.4ms  p(95)=456ms    p(99)=890ms
     http_req_failed................: 0.5%
     http_req_receiving.............: avg=15.3ms   p(95)=45.2ms   p(99)=78.3ms
     http_req_sending...............: avg=5.2ms    p(95)=12.1ms   p(99)=25.3ms
     http_req_tls_handshaking.......: avg=0ms      p(95)=0ms      p(99)=0ms
     http_req_waiting...............: avg=102.9ms  p(95)=425ms    p(99)=850ms
     http_reqs......................: 10000   1666.67/sec
     iteration_duration.............: avg=1.2s     p(95)=2.1s     p(99)=3.2s
     iterations.....................: 10000   1666.67/sec
     vus............................: 100     max=100
```

### 성능 분석

**좋은 성능** ✅:
- p95 응답시간 < 500ms
- p99 응답시간 < 1000ms
- 실패율 < 1%
- CPU/메모리 사용률 안정적

**나쁜 성능** ❌:
- p95 응답시간 > 1000ms
- p99 응답시간 > 2000ms
- 실패율 > 5%
- 응답시간 급증

---

## 🔍 고급 사용법

### 1. CSV 데이터 소스 사용

**test-data.csv**:
```csv
email,password
user1@test.com,pass123
user2@test.com,pass123
user3@test.com,pass123
```

**스크립트**:
```javascript
import { SharedArray } from 'k6/data';

const data = new SharedArray('users', function () {
  return open('./test-data.csv')
    .split('\n')
    .slice(1)
    .map(line => {
      const [email, password] = line.split(',');
      return { email, password };
    });
});

export default function () {
  const user = data[__VU % data.length];
  // ... 테스트 로직
}
```

### 2. 커스텀 메트릭

```javascript
import { Counter, Histogram } from 'k6/metrics';

const errors = new Counter('errors');
const apiDuration = new Histogram('api_duration');

export default function () {
  const res = http.get('http://localhost:8080/api/v1/product/list');

  if (res.status !== 200) {
    errors.add(1);
  }

  apiDuration.value = res.timings.duration;
}
```

### 3. 조건부 실행

```javascript
export const options = {
  scenarios: {
    average_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '1m30s', target: 100 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
};
```

---

## 🐳 Docker로 실행

```bash
# 기본 테스트
docker run -v $(pwd):/scripts grafana/k6 run /scripts/k6/scripts/basic-test.js

# 결과를 파일로 저장
docker run -v $(pwd):/scripts grafana/k6 run --out json=/scripts/results.json /scripts/k6/scripts/basic-test.js
```

---

## 📈 Grafana와 연동

### 실시간 대시보드 설정

1. **Grafana 데이터소스 추가** (Prometheus)
2. **k6 테스트 실행**:
```bash
k6 run --out grafana k6/scripts/basic-test.js
```

3. **Grafana 대시보드에서 메트릭 확인**

---

## ⚠️ 주의사항

### 1. 프로덕션 환경 테스트 금지
- 개발/스테이징 환경에서만 테스트
- 프로덕션 서버에 대한 무단 부하 테스트는 불법

### 2. 서버 리소스 확인
- 테스트 전에 서버 상태 확인
- 동시에 여러 테스트 실행 금지

### 3. 데이터베이스 영향
- 실제 데이터 변경 가능성
- 트랜잭션 테스트 시 롤백 고려

### 4. 테스트 데이터
- 테스트 계정/데이터 별도 준비
- 프로덕션 데이터 사용 금지

---

## 🎓 테스트 전략

### 📌 추천 테스트 순서

#### **Phase 1: 도메인별 개별 테스트** (각각 10분)

```bash
# 1. Product 테스트 (가장 부하가 많은 영역)
k6 run k6/scripts/product-test.js

# 2. Order 테스트 (중간 부하)
k6 run k6/scripts/order-test.js

# 3. Payment 테스트 (적은 부하, 높은 신뢰도)
k6 run k6/scripts/payment-test.js
```

#### **Phase 2: 통합 테스트** (20분)

```bash
# 전체 시스템 통합 테스트
k6 run k6/scripts/integrated-test.js
```

#### **Phase 3: 스파이크 & 스트레스 테스트** (각각 10분)

```bash
# 스파이크 시나리오
k6 run k6/scripts/spike-test.js

# 스트레스 테스트
k6 run --vus 200 --duration 5m k6/scripts/product-test.js
```

---

### 📊 테스트 결과 분석 체크리스트

각 테스트 후 확인할 항목:

**Product 도메인**:
- [ ] p95 응답시간 < 500ms
- [ ] 실패율 < 1%
- [ ] DB 쿼리 성능 (index 활용)

**Order 도메인**:
- [ ] 트랜잭션 무결성 (동시성 제어)
- [ ] 장바구니 동시성 처리
- [ ] p95 응답시간 < 1000ms

**Payment 도메인**:
- [ ] p95 응답시간 < 2000ms
- [ ] 결제 실패율 < 0.05%
- [ ] 환불 처리 안정성
- [ ] 동시 결제 처리 능력

---

### 🔍 병목 지점 파악 가이드

**응답 시간 증가**:
```bash
# Spring Boot 로그 확인
docker logs spring-boot-app | grep -i duration

# DB 슬로우 쿼리 확인
docker logs postgresql | grep duration
```

**메모리 누수**:
```bash
# Grafana에서 메모리 사용률 모니터링
# http://localhost:3030 → Explore → Loki/Prometheus
```

**데이터베이스 병목**:
```bash
# PostgreSQL 활성 연결 확인
docker exec dev-db psql -U dev -c "SELECT count(*) FROM pg_stat_activity;"
```

**Redis 병목**:
```bash
# Redis 메모리/성능 확인
docker exec dev-redis redis-cli info stats
```

---

## 🔧 커스텀 테스트 작성

### 템플릿

```javascript
import http from 'k6/http';
import { check, group, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 100 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = 'http://localhost:8080/api';

export default function () {
  group('API 테스트', () => {
    const res = http.get(`${BASE_URL}/v1/product/list`);

    check(res, {
      'status is 200': (r) => r.status === 200,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });
  });

  sleep(1);
}
```

---

**마지막 업데이트**: 2025-11-13
