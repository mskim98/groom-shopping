# Groom Shopping

상품을 주문·결제하고 쿠폰과 추첨 이벤트를 운영하는 이커머스 백엔드.

한정 수량 자원을 다투는 경로(선착순 쿠폰 발급 · 재고 차감)와 되돌릴 수 없는 외부 호출을 다루는 경로(PG 결제)를 서로 다른 종류의 문제로 보고, 각 경로에 다른 동시성·정합성 전략을 적용한 구조.

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21 · Spring Boot 3.5.7 |
| 영속 | PostgreSQL · Spring Data JPA · Flyway |
| 캐시·락 | Redis · Redisson |
| 메시징 | Kafka · Kafka Streams (Processor API) |
| 복원력 | Resilience4j · Spring Retry |
| 인증 | Spring Security · JWT (jjwt) |
| 관측 | Actuator · Micrometer · Prometheus · Grafana · Loki |
| 외부 연동 | Toss Payments · AWS S3 |
| 프론트엔드 | Next.js 16 · React 19 · TypeScript |
| 부하 테스트 | k6 |

## 아키텍처

```
브라우저
   │
   ▼
Nginx (:80)
   ├── /          → Next.js (:3000)
   └── /api/v1/   → Spring Boot (:8080, context-path /api)
                        │
      ┌─────────────────┼─────────────────┬──────────────┐
      ▼                 ▼                 ▼              ▼
  PostgreSQL          Redis             Kafka      Toss Payments
 (원천 데이터)   (게이트·예약·캐시)  (순서·지연 실행)   (외부 PG)
```

- Redis 는 정확성의 근거가 아니라 **부하 필터**. 최종 정합은 PostgreSQL 이 책임
- Kafka 는 **선착순의 기준점**. 파티션 로그의 오프셋이 순서의 진실
- 외부 PG 호출은 서킷 브레이커 뒤에 두되 승인과 취소를 별도 회로로 분리

## 핵심 설계

### 1. 선착순 쿠폰 발급

발급 경로에서 분산 락과 DB 를 모두 걷어낸 구조.

| 구간 | 담당 | 역할 |
|---|---|---|
| 접수 | Redis Lua | 마감·중복을 한 왕복에 판정하는 부하 필터 |
| 순서 | Kafka 오프셋 | 파티션 키 `couponId`, 파티션 안에서 결정적 전순서 |
| 확정 | 단일 컨슈머 | 파티션당 1명이라 상호배제를 락이 아니라 소유권으로 확보 |
| 최후 방어 | DB 제약 | `UNIQUE(coupon_id, user_id)` · `UPDATE ... WHERE quantity > 0` |

API 는 게이트 통과와 Kafka 발행까지만 수행하고 `requestId` 를 반환(202 Accepted). 결과는 상태 조회로 확인. 응답 경로에 DB 쓰기가 없어 쿠폰 스파이크가 주문·결제의 커넥션을 잠식하지 않음.

Lua 스크립트에서 **중복 검사를 재고 검사보다 앞에** 둔 이유는 실패 집계 때문. 뒤에 두면 재고가 0 이 된 뒤로는 이미 받은 사용자의 재요청까지 "품절"로 응답돼 중복과 품절이 한 버킷에 섞임.

관련 코드 — `application/coupon/CouponStockRedisRepository.java` · `CouponAsyncIssueService.java` · `infrastructure/kafka/CouponIssueRequestConsumer.java`

### 2. PG 결제 보상 파이프라인

외부 승인은 비가역, 내부 후처리는 가역이라 트랜잭션 경계를 분리.

- 승인 호출 `NOT_SUPPORTED`(비트랜잭션) / DB 후처리 `REQUIRES_NEW`(독립 트랜잭션)
- 후처리 실패 시 PG 취소를 즉시 호출하고, 그마저 실패하면 보상 테이블에 적재해 배치가 재시도
- **의도 기록을 먼저 커밋한 뒤 실행** — 이 경로의 진입 원인이 DB 장애일 때 장부까지 함께 사라지는 것을 차단
- 재시도는 지수 백오프 2·4·8·16분, 총 5회 시도 후 `GIVEN_UP` 전환 + DLQ 이관
- 보상 레코드는 4상태 전이표로 관리(`PENDING`·`FAILED`·`SUCCEEDED`·`GIVEN_UP`). 종단 상태는 재전이를 거부하고, 거부 시 재시도 횟수도 오르지 않도록 판정을 카운트보다 앞에 배치
- 멱등키는 재시도 사이에 불변인 값 사용. 승인 회로(`toss`)와 취소 회로(`toss-cancel`)를 나눠 한쪽 장애가 반대쪽 보상을 막지 않게 함

관련 코드 — `application/payment/PaymentApplicationService.java` · `PaymentCompensationService.java` · `domain/payment/model/PaymentCompensation.java`

### 3. 쿠폰 자동 비활성화 (지연 스케줄링)

폴링 스캔 대신 Kafka Streams StateStore + Punctuator 로 구현.

- 실행 예정 시각을 **초 단위로 그룹화한 키**로 영구 StateStore 에 적재. 같은 초의 이벤트는 목록에 누적
- `WALL_CLOCK_TIME` punctuator 가 1초마다 도래분만 Range Query 로 조회 후 forward
- `STREAM_TIME` 을 쓰지 않은 이유 — 신규 레코드 없이는 전진하지 않아 유입이 멈춘 구간에서 대기 중인 쿠폰이 영원히 비활성화되지 않음
- changelog 기반이라 서버 재시작 후에도 대기 작업이 잔존
- 실패 이벤트는 전용 DLQ 토픽(`coupon-delay-dlq`)과 별도 컨슈머로 격리

관련 코드 — `infrastructure/kafka/stream/CouponDelayProcessor.java`

### 4. 추첨 예약 실행

예약을 프로세스 밖(Redis Sorted Set)에 두어 재기동에 견디는 구조.

- `score` = 실행 시각(epoch ms). `rangeByScore(0, now)` 한 번이 곧 "지금 실행할 것 전부"
- 폴링은 조회와 Kafka 발행까지만 담당하고 추첨 로직은 컨슈머가 실행. 루프가 무거우면 다음 주기가 밀려 지연이 누적되기 때문
- **발행 성공 콜백에서만 ZSet 제거** — 먼저 지우면 유실, 발행 후 못 지우면 중복인데 되돌릴 수 있는 중복을 선택
- 컨슈머는 수동 ACK, 추첨 실행이 끝난 뒤에만 커밋
- 폴링 주기 `raffle.drawing.poll-delay-ms`(기본 60,000ms)가 곧 실행 지연의 상한

관련 코드 — `infrastructure/redis/RaffleDelayedDrawingQueue.java` · `application/raffle/RaffleScheduler.java`

### 5. 재고 차감 전략

차감 전략을 인터페이스로 분리해 설정값 하나로 교체 가능.

| 전략 | 설정값 | 특성 |
|---|---|---|
| 낙관적 락 | `optimistic` | `@Version` + `@Retryable(3회)`, 지터 백오프 |
| 조건부 UPDATE | `conditional` | `WHERE stock >= :quantity`, DB 가 원자적으로 판정 |
| 비관적 락 | `pessimistic` **(기본값)** | 행 잠금으로 직렬화, 재시도 미발생 |

`stock.decrease-strategy` 로 전환. 기본값이 비관적 락인 것은 단일 상품 행에 부하가 몰리는 워크로드에서 재시도 낭비가 직렬화 비용보다 컸기 때문.

결제 진입 직전 Redis `DECR` 로 재고를 선점해 부족분을 PG 호출 전에 차단하는 2단 방어. 주문 생성 시점의 검증은 읽고 나서 판단하는 비원자 검사라 판단과 차감 사이에 틈이 남고, 선점 게이트가 그 틈을 전담.

Redis 선점 수와 DB 재고의 어긋남은 배치로 대조하고 Micrometer 게이지로 노출(`stock_drift_*`). 자동 보정은 두지 않고 **탐지까지로 한정** — 보정 자체가 새 드리프트 원인이 될 수 있기 때문.

관련 코드 — `application/product/*StockDecrementer.java` · `infrastructure/product/StockReconciliationJob.java`

## 프로젝트 구조

```
backend/            Spring Boot 서버
  src/main/java/groom/backend/
    domain/         엔티티·리포지터리 인터페이스 (auth, coupon, notification, order, payment, product, raffle)
    application/    유스케이스·트랜잭션 경계
    infrastructure/ Kafka·Redis·S3·보안·SSE·외부 PG 연동
    interfaces/     컨트롤러·영속 어댑터
    common/         공통 응답·예외
  src/main/resources/db/migration/   Flyway 마이그레이션 (V1~V13)
frontend/           Next.js 프론트엔드
nginx/              리버스 프록시 설정
k6/                 부하 테스트 스크립트
monitoring/         Prometheus·Grafana·Loki 설정
```

## 실행

### 사전 준비

프로젝트 루트에 `.env` 생성.

```bash
POSTGRES_USER=<사용자>
POSTGRES_PASSWORD=<비밀번호>
POSTGRES_DB=<DB 이름>
```

`.env` 는 gitignore 대상. 저장소에 커밋하지 않음.

### 개발 환경 — 백엔드 로컬 + 나머지 Docker

```bash
# 인프라 기동 (DB · Redis · Kafka · Nginx · Next.js · 관측 스택)
docker compose -f docker-compose.dev.yml up -d

# 백엔드 실행
cd backend && ./gradlew bootRun
```

### 전체 Docker

```bash
docker compose up -d
```

| 서비스 | 포트 | 비고 |
|---|---|---|
| Nginx | 80 | 진입점 |
| Backend | 8080 | context-path `/api` |
| Frontend | 3000 | Next.js |
| PostgreSQL | 5432 | |
| Redis | 6379 | |
| Kafka | 9092 | Zookeeper 동반 |
| Grafana · Prometheus | — | `docker-compose.dev.yml` 한정 |

## API

Swagger UI — `http://localhost:8080/api/swagger-ui.html`

| 경로 | 도메인 |
|---|---|
| `/v1/auth` | 회원가입·로그인·토큰 재발급 |
| `/v1/product` | 상품 조회·관리 |
| `/v1/cart` | 장바구니 |
| `/v1/order` | 주문 생성·조회 |
| `/v1/payment` | Toss 결제 승인·취소 |
| `/v1/coupon` | 쿠폰 발급·조회 |
| `/v1/raffles` | 추첨 응모·결과 |
| `/v1/notification` | SSE 실시간 알림 |

## 데이터베이스

스키마는 **Flyway 단독 관리**. `ddl-auto` 로 스키마를 만들지 않음.

마이그레이션 추가 시 `backend/src/main/resources/db/migration/V{N}__{설명}.sql` 규칙을 따름.

## 관측

- Actuator — `/api/actuator/health` · `/api/actuator/prometheus`
- 커스텀 지표 — `stock_optimistic_retries`(전략별 재시도 분포) · `stock_drift_*`(Redis·DB 재고 드리프트) · Resilience4j 서킷 상태
- 로그는 Promtail → Loki → Grafana 경로로 수집

## 부하 테스트

```bash
k6 run k6/scripts/coupon-issue-test.js       # 선착순 쿠폰 발급
k6 run k6/scripts/stock-concurrency-test.js  # 재고 동시 차감
k6 run k6/scripts/circuit-breaker-test.js    # PG 장애 주입 시 서킷 동작
k6 run k6/scripts/integrated-test.js         # 주문~결제 통합 시나리오
```

`k6/wiremock/` 에 정상·지연 두 가지 PG 스텁 매핑이 있어 장애 주입 대조가 가능.
