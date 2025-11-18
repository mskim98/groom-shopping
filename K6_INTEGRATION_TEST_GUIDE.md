# k6 통합 부하 테스트 가이드 (Flyway 데이터 기반)

## 📋 개요

**작성 일시**: 2025-11-18
**버전**: 2.0 (Flyway 데이터 기반 완전 재작성)
**테스트 대상**: Groom Shopping 통합 플로우

이 가이드는 **Flyway 마이그레이션으로 생성된 실제 데이터**를 기반으로 한 k6 통합 부하 테스트 스크립트입니다. 하드코딩된 UUID를 제거하고, 동적으로 데이터를 로드하여 실제 프로덕션 시나리오를 재현합니다.

---

## 🎯 테스트 목표

### 1. 전체 쇼핑 플로우 검증
- ✅ 실제 사용자 데이터 (21명) 기반 로그인
- ✅ 실제 상품 (50개) 조회 및 선택
- ✅ 장바구니 관리 (추가, 제거, 수량 변경)
- ✅ 주문 생성 및 결제
- ✅ 래플 참여 (TICKET 구매 후)

### 2. 성능 지표 측정
| 지표 | 목표 | 설명 |
|------|------|------|
| **p95 응답시간** | < 1000ms | 95%의 요청이 1초 이내 완료 |
| **p99 응답시간** | < 2000ms | 99%의 요청이 2초 이내 완료 |
| **실패율** | < 0.05% | 99.95% 이상 성공률 |
| **동시 사용자** | 최대 50 VU | 최대 동시성 테스트 |

### 3. 병목 지점 식별
- 데이터베이스 쿼리 성능
- 결제 API 응답 시간
- 동시성 제어 (Race Condition)

---

## 📊 테스트 데이터 구조

### Flyway 마이그레이션 데이터

#### 사용자 데이터 (21명)
```
V2__Add_test_users.sql
├── admin@test.com (ROLE_ADMIN, BRONZE)
├── user_1@test.com ~ user_20@test.com (ROLE_USER, BRONZE)
└── 각 사용자마다 빈 Cart 생성
```

**로그인 정보**:
- 이메일: `user_1@test.com` ~ `user_20@test.com`
- 비밀번호: `password123` (bcrypt 해시)
- 관리자 계정: `admin@admin.com` / `admin123` (V7)

#### 상품 데이터 (50개)
```
V3__Add_test_products.sql

GENERAL 카테고리 (20개)
├── Premium Laptop Pro (1,500,000원, 50개)
├── Wireless Mouse Ultra (45,000원, 200개)
├── USB-C Hub Pro (89,000원, 150개)
├── ... (17개 더)
└── Cable HDMI Premium (18,000원, 800개)

TICKET 카테고리 (10개)
├── Raffle Ticket #1 (10,000원, 100개)
├── Raffle Ticket #2 (10,000원, 100개)
├── ... (8개 더)
└── Raffle Ticket #10 (10,000원, 100개)

RAFFLE 카테고리 (10개)
├── Raffle Prize Item #1 (50,000원, 1-20개)
├── Raffle Prize Item #2 (50,000원, 1-20개)
├── ... (8개 더)
└── Raffle Prize Item #10 (50,000원, 1-20개)
```

#### 쿠폰 데이터 (6개)
```
V7__Add_coupons_admin_user_and_raffle.sql

1. Summer Sale 10% (PERCENT, 10% 할인)
2. New Member Welcome (PERCENT, 15% 할인)
3. 5000 Won Discount (DISCOUNT, 5,000원 할인)
4. 10000 Won Discount (DISCOUNT, 10,000원 할인)
5. Minimum 30000 Discount (MIN_COST_AMOUNT, 3,000원 할인)
6. Max Discount Percent (MAX_DISCOUNT_PERCENT, 20% 할인, 최대 15,000원)
```

#### 래플 데이터 (10개)
```
V7__Add_coupons_admin_user_and_raffle.sql

각 TICKET × RAFFLE 상품 조합으로 10개 생성
├── raffleId: 자동 증가 (1-10)
├── 상태: ACTIVE
├── 우승자: 5명
├── 최대 참여: 사용자당 3회
└── 참여 기간: 2024-01-01 ~ 2025-12-31
```

---

## 🔧 k6 통합 테스트 스크립트 구조

### setup() 함수 - 데이터 초기화 (한 번만 실행)

```javascript
export function setup() {
  // 1️⃣ 모든 상품 조회 (page=0&size=100)
  // 2️⃣ Admin 계정으로 로그인 후 쿠폰 조회
  // 3️⃣ 모든 래플 조회 (page=0&size=100)

  return { products, coupons, raffles }; // 다음 VU에 전달
}
```

**특징**:
- 테스트 시작 전 **단 1회만 실행**
- 50개 상품, 6개 쿠폰, 10개 래플을 메모리에 로드
- 모든 VU가 동일한 데이터 사용

### default(setupData) 함수 - 메인 테스트 로직

각 VU가 다음 플로우를 반복 실행:

#### Phase 1: 인증 (0.5초)
```javascript
POST /v1/auth/login
├── 입력: user_1@test.com ~ user_20@test.com (VU 분배)
├── 비밀번호: password123
└── 출력: JWT Access Token
```

**사용자 분배**:
```javascript
const userNum = ((__VU - 1) % 20) + 1;
// VU 1, 21, 41 → user_1@test.com
// VU 2, 22, 42 → user_2@test.com
// ...
// VU 50 → user_10@test.com
```

#### Phase 2: 상품 조회 (1.5초)
```
①️⃣ 상품 목록 조회
  GET /v1/product?page=0&size=20&sort=id,DESC
  └─ p95 < 500ms (read-only, 인덱스 활용)

②️⃣ 상품 상세 조회
  GET /v1/product/{id}
  ├─ GENERAL 카테고리 상품 중 선택
  └─ p95 < 500ms
```

#### Phase 3: 장바구니 추가 (1.0초)
```
①️⃣ 장바구니 조회
  GET /v1/cart
  └─ User별 Cart 객체 확인

②️⃣ 장바구니에 상품 추가
  POST /v1/cart/add
  ├─ productId: 선택된 GENERAL 상품 UUID
  ├─ quantity: 1
  └─ p95 < 1000ms
```

#### Phase 4: 주문 생성 (1.5초)
```
①️⃣ 주문 생성 (쿠폰 미적용)
  POST /v1/order
  ├─ couponId: null
  ├─ Status: 201 (Created)
  └─ p95 < 2000ms (재고 감소 포함)

②️⃣ 장바구니 비우기
  DELETE /v1/cart/remove
  └─ 다음 테스트를 위해 정리
```

#### Phase 5: 래플 참여 (2.5초)
```
①️⃣ TICKET 상품 추가
  POST /v1/cart/add
  └─ productId: TICKET 카테고리 상품 선택

②️⃣ 티켓 주문 생성
  POST /v1/order
  └─ Status: 201

③️⃣ 결제 승인
  POST /v1/payment/confirm/test
  ├─ paymentKey: test_key_ticket_{VU}_{ITER}
  └─ amount: 주문 총액

④️⃣ 래플 참여
  POST /v1/raffle/enter
  ├─ raffleId: 선택된 래플 ID
  └─ 사용자당 최대 3회
```

#### Phase 6: 조회 API (2.0초)
```
①️⃣ 결제 내역 조회
  GET /v1/payment/my
  └─ 사용자 본인의 모든 결제 내역

②️⃣ 주문 목록 조회
  GET /v1/order?page=0&size=20
  └─ 사용자 본인의 모든 주문

③️⃣ 알림 조회
  GET /v1/notification?page=0&size=20
  └─ 재고 부족 알림 등
```

**전체 반복 시간**: 약 10초 / VU

---

## 🚀 실행 방법

### 준비 사항

1. **백엔드 서버 실행**
```bash
# Spring Boot 시작
./gradlew bootRun
```

2. **데이터베이스 초기화**
```bash
# Flyway 마이그레이션 자동 실행됨
# V1 ~ V7 모두 적용되는지 확인
docker logs spring-boot-app | grep Flyway
```

3. **API 확인**
```bash
# Swagger UI에서 데이터 확인
http://localhost:8080/swagger-ui.html

# 또는 curl로 확인
curl http://localhost:8080/api/v1/product?page=0&size=10
```

### 기본 실행

```bash
# 통합 테스트 기본 실행
k6 run k6/scripts/integrated-test.js
```

**예상 실행 시간**: 약 5~6분
**최대 동시 사용자**: 50 VU

### 커스텀 옵션

#### 🔹 VU 및 기간 조정
```bash
# 100 VU로 10분 테스트
k6 run --vus 100 --duration 10m k6/scripts/integrated-test.js

# 50 VU, 5분 테스트
k6 run --vus 50 --duration 5m k6/scripts/integrated-test.js
```

#### 🔹 결과를 파일로 저장
```bash
# JSON 형식 저장
k6 run --out json=results-$(date +%Y%m%d-%H%M%S).json k6/scripts/integrated-test.js

# CSV 형식 저장 (Grafana 연동)
k6 run --out csv=results.csv k6/scripts/integrated-test.js
```

#### 🔹 Grafana 실시간 모니터링
```bash
# Grafana 대시보드에 실시간 메트릭 전송
k6 run --out grafana k6/scripts/integrated-test.js
```

> **필수**: Grafana + Prometheus + Grafana Loki 환경이 Docker Compose로 실행 중이어야 합니다.

#### 🔹 반복 횟수 기반 테스트
```bash
# 각 VU마다 10회씩 반복
k6 run --vus 20 --iterations 10 k6/scripts/integrated-test.js
```

---

## 📊 테스트 결과 분석

### 성공 기준

**Phase별 성공 지표**:

| Phase | 기준 | 설명 |
|-------|------|------|
| 인증 | p95 < 500ms | JWT 토큰 생성 |
| 상품 조회 | p95 < 500ms | 캐싱 및 인덱스 활용 |
| 주문 생성 | p95 < 2000ms | 재고 감소, 결제 생성 |
| 결제 | p95 < 2000ms | Toss 외부 API 제외 |
| 전체 | 실패율 < 0.05% | 99.95% 이상 성공 |

### 예상 결과

```
     data_received..................: 15 MB    2.5 MB/s
     data_sent......................: 5 MB     0.8 MB/s
     http_req_blocked...............: avg=2.1ms    p(95)=8.2ms    p(99)=15.3ms
     http_req_connecting............: avg=1.3ms    p(95)=4.1ms    p(99)=8.5ms
     http_req_duration..............: avg=450ms    p(95)=950ms    p(99)=1850ms
     http_req_failed................: 0.02%
     http_req_receiving.............: avg=22.1ms   p(95)=65.2ms   p(99)=120.3ms
     http_req_sending...............: avg=8.3ms    p(95)=18.2ms   p(99)=35.1ms
     http_req_tls_handshaking.......: avg=0ms      p(95)=0ms      p(99)=0ms
     http_req_waiting...............: avg=419ms    p(95)=880ms    p(99)=1750ms
     http_reqs......................: 5000      13.89/sec
     iteration_duration.............: avg=9.8s     p(95)=15.2s    p(99)=22.5s
     iterations.....................: 500       1.39/sec
     vus............................: 50        max=50
     vus_max........................: 50        max=50
```

**분석**:
- ✅ p95 응답시간 950ms < 1000ms (목표 달성)
- ✅ p99 응답시간 1850ms < 2000ms (목표 달성)
- ✅ 실패율 0.02% < 0.05% (목표 달성)

### 병목 지점 식별

#### 응답 시간이 느린 경우

```bash
# 1️⃣ 느린 API 찾기 (k6 결과에서)
- http_req_duration: avg가 1000ms 이상이면 해당 API 확인

# 2️⃣ Spring Boot 로그에서 느린 쿼리 확인
docker logs spring-boot-app | grep -i "duration" | sort -r

# 3️⃣ DB 슬로우 쿼리 확인
docker exec dev-db psql -U dev -c "
  SELECT query, calls, mean_time FROM pg_stat_statements
  WHERE mean_time > 100
  ORDER BY mean_time DESC;
"

# 4️⃣ Grafana Loki에서 로그 분석
# http://localhost:3030/explore → Loki → 쿼리 입력
```

#### 실패율이 높은 경우

```bash
# 1️⃣ k6 결과에서 실패한 요청의 상태 코드 확인
http_req_failed: 5%

# 2️⃣ 에러 타입 확인
- 400 Bad Request → 요청 형식 확인
- 401 Unauthorized → 토큰 만료
- 404 Not Found → 엔드포인트 오류
- 500 Internal Server Error → 서버 오류

# 3️⃣ Spring Boot 에러 로그 확인
docker logs spring-boot-app | grep -i "ERROR" | tail -50

# 4️⃣ 동시성 문제 확인
# Race Condition으로 인한 재고 중복 감소 등
SELECT product_id, COUNT(*) as cnt FROM order_item
GROUP BY product_id
HAVING COUNT(*) > expected_count;
```

---

## 🔍 고급 사용법

### 1. 스트레스 테스트 (점진적 증가)

```bash
# 최대 부하까지 천천히 증가
k6 run --stage 1m:50vus --stage 2m:100vus --stage 1m:0vus \
  k6/scripts/integrated-test.js
```

### 2. 스파이크 테스트 (갑작스런 증가)

```bash
# 갑작스럽게 트래픽 급증 시뮬레이션
k6 run --stage 30s:10vus --stage 10s:100vus --stage 30s:0vus \
  k6/scripts/integrated-test.js
```

### 3. 특정 사용자만 테스트

```bash
# 환경 변수로 특정 사용자로 제한
k6 run -e USER_RANGE=1-5 k6/scripts/integrated-test.js
```

### 4. 결과 비교

```bash
# 이전 결과와 비교
k6 run --out json=results-new.json k6/scripts/integrated-test.js

# Python으로 비교 분석
python3 scripts/compare_k6_results.py results-old.json results-new.json
```

---

## 🐳 Docker 실행

### Docker 단독 실행

```bash
docker run -v $(pwd):/scripts grafana/k6:latest \
  run /scripts/k6/scripts/integrated-test.js
```

### Docker Compose 내 k6 서비스

```yaml
# docker-compose.dev.yml에 추가
k6-test:
  image: grafana/k6:latest
  volumes:
    - ./k6:/scripts
  command: run /scripts/scripts/integrated-test.js
  networks:
    - monitoring
```

실행:
```bash
docker-compose -f docker-compose.dev.yml run k6-test
```

---

## ⚠️ 주의사항

### 1. 데이터 무결성
- ⚠️ **프로덕션에서 실행 금지**: 실제 사용자 데이터가 변경됨
- ✅ 개발/스테이징 환경에서만 실행
- 🔄 매 테스트 후 데이터베이스 리셋 권장

```bash
# 데이터베이스 초기화 (Flyway 재실행)
docker-compose down -v  # 볼륨 삭제
docker-compose up -d    # 새로 시작
```

### 2. 서버 리소스
- 🖥️ 백엔드 메모리: 최소 2GB
- 🗄️ 데이터베이스 연결: 최대 50 (테스트), 프로덕션 100+ 추천
- 💾 디스크: k6 결과 저장 시 충분한 공간 확인

### 3. 동시성 제한
- 🔒 동일 사용자로 동시 요청 금지 (토큰 충돌)
- ✅ 테스트가 자동으로 20명 사용자 분배
- 🔄 각 VU는 독립적인 토큰 사용

### 4. 테스트 데이터 변경
현재 스크립트는 **Flyway 데이터에만 의존**:
- ❌ 테스트 사용자 추가/삭제 금지
- ❌ 상품 추가/삭제 금지 (ID 변경)
- ✅ 쿠폰/래플은 동적으로 로드됨 (추가 가능)

---

## 🔧 트러블슈팅

### 401 Unauthorized 에러

```javascript
// 원인: 토큰 파싱 오류
authToken = loginRes.json('accessToken');  // ✅ 올바름
authToken = loginRes.json('data.accessToken');  // ❌ 오류

// 해결: LoginResponse 구조 확인
curl http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user_1@test.com","password":"password123"}' | jq
```

### 400 Bad Request (장바구니 추가)

```javascript
// 원인: productId UUID 형식 오류
const productId = "550e8400-e29b-41d4-a716-446655440000";  // ✅ UUID
const productId = "1";  // ❌ Integer

// 해결: setup()에서 로드한 product.id 사용
```

### 404 Not Found (상품 상세)

```javascript
// 원인: 존재하지 않는 상품 ID
const productId = "invalid-uuid-format";

// 해결: setup()에서 로드한 실제 상품 ID 사용
```

### 메모리 부족 (k6 OOM)

```bash
# k6 메모리 증가
k6 run --system-tags=url,method,status k6/scripts/integrated-test.js \
  --max-vus 100

# 또는 결과 샘플링
k6 run --linger-time=5s k6/scripts/integrated-test.js
```

### 데이터 로드 실패

```javascript
// 문제: API 응답 오류 (setup 단계)
// 해결:
1. 백엔드 서버 상태 확인
2. Flyway 마이그레이션 확인 (V7까지 완료)
3. 통신 확인: curl http://localhost:8080/api/v1/product
```

---

## 📈 성능 최적화 팁

### 1. 쿼리 최적화
```sql
-- Product 테이블 인덱스 확인
SELECT * FROM pg_indexes WHERE tablename = 'product';

-- 필요시 인덱스 추가
CREATE INDEX idx_product_category ON product(category);
CREATE INDEX idx_product_status ON product(status);
```

### 2. 데이터베이스 연결 풀
```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 100  # 동시 50 VU 기준
      minimum-idle: 20
      connection-timeout: 30000
```

### 3. 캐싱 전략
```java
// Product 목록은 자주 안 변하므로 캐싱
@Cacheable(value = "products", key = "#page + '-' + #size")
public Page<ProductResponse> getProducts(int page, int size) { ... }
```

### 4. 정렬 및 페이지네이션
```bash
# k6에서 정렬 쿼리 사용
GET /v1/product?page=0&size=20&sort=id,DESC
```

---

## 📚 참고 자료

### Flyway 마이그레이션
- V1: 초기 스키마 (13개 테이블)
- V2: 테스트 사용자 (21명)
- V3: 테스트 상품 (50개)
- V4: snake_case 변환
- V5: Payment 업데이트
- V6: Raffle 카운터
- V7: 쿠폰, 관리자, 래플 (10개)

### 테스트 스크립트
```
k6/scripts/
├── integrated-test.js      ⭐ 새로운 통합 테스트 (권장)
├── product-test.js         (레거시, 업데이트 필요)
├── order-test.js           (레거시, 업데이트 필요)
├── payment-test.js         (레거시, 업데이트 필요)
├── basic-test.js           (레거시)
├── user-flow-test.js       (레거시)
└── spike-test.js           (레거시)
```

### 문서
```
├── K6_LOAD_TEST_GUIDE.md           (구 가이드, 참고용)
├── K6_BACKEND_API_MAPPING.md       (API 매핑, 참고용)
└── K6_INTEGRATION_TEST_GUIDE.md    ⭐ 이 파일 (현재)
```

---

## ✅ 체크리스트

테스트 전에 확인:

- [ ] 백엔드 서버 실행 중 (localhost:8080)
- [ ] 데이터베이스 초기화 (Flyway V1-V7)
- [ ] API 통신 확인 (Swagger UI 접근 가능)
- [ ] k6 설치 확인 (`k6 version`)
- [ ] 테스트 환경 (Dev/Staging 확인)
- [ ] 디스크 공간 충분 (결과 파일 저장)

테스트 후 확인:

- [ ] 결과 저장 (--out json=results.json)
- [ ] 성능 목표 달성 확인
  - [ ] p95 < 1000ms
  - [ ] p99 < 2000ms
  - [ ] 실패율 < 0.05%
- [ ] 데이터 무결성 확인
  - [ ] 주문 생성 수 확인
  - [ ] 결제 상태 확인
- [ ] 에러 로그 검토
- [ ] Grafana 대시보드 분석 (필요시)

---

## 📞 문의

테스트 결과 분석 또는 최적화 관련:
- Grafana: http://localhost:3030 (admin/admin)
- Prometheus: http://localhost:9090
- Loki: http://localhost:3100
- Spring Boot 로그: `docker logs -f spring-boot-app`

---

**마지막 업데이트**: 2025-11-18
**작성**: Claude Code
**참고**: 이 가이드는 Flyway 마이그레이션 V1-V7 기준입니다.
