# Postman을 이용한 실시간 SSE 알림 테스트 가이드

이 가이드는 Postman을 사용하여 로그인부터 실시간 SSE 알림 수신까지 전체 플로우를 테스트하는 방법을 설명합니다.

---

## 📋 사전 준비사항

1. **Spring Boot 애플리케이션 실행 중**
2. **PostgreSQL 데이터베이스 연결됨**
3. **Kafka 실행 중** (Docker: `docker run -p 9092:9092 apache/kafka`)
4. **Redis 실행 중**

---

## 🚀 테스트 시나리오

### 시나리오 개요
1. **User A**: 로그인 → 장바구니에 제품 추가 → SSE 연결
2. **User B**: 로그인 → 제품 구매 (임계값 이하로 재고 감소)
3. **User A**: SSE로 실시간 알림 수신 확인

---

## 1단계: User A 로그인

### Request 설정
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/auth/login`
- **Headers**: 
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "email": "admin@test.com",
  "password": "1234"
}
```

### 응답 예시
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "name": "이준원",
  "role": "ROLE_ADMIN"
}
```

### ⚠️ 중요: `accessToken` 값을 복사해두세요!

---

## 2단계: User A 장바구니에 제품 추가

### Request 설정
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/cart/add`
- **Headers**: 
  - `Content-Type: application/json`
  - `Authorization: Bearer {1단계에서 받은 accessToken}`
- **Body** (raw JSON):
```json
{
  "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758",
  "quantity": 2
}
```

### 제품 ID 확인 방법
```sql
SELECT id, name, stock, threshold_value 
FROM product 
WHERE is_active = true 
LIMIT 5;
```

### 응답 예시
```json
{
  "cartId": 4,
  "cartItemId": 1,
  "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758",
  "quantity": 2,
  "message": "장바구니에 추가되었습니다."
}
```

---

## 3단계: User A SSE 연결 (Postman)

### Request 설정
- **Method**: `GET`
- **URL**: `http://localhost:8080/api/notifications/stream`
- **Headers**: 
  - `Authorization: Bearer {1단계에서 받은 accessToken}`
  - `Accept: text/event-stream`

### Postman SSE 설정 방법

#### 방법 1: Postman Native SSE (권장)
1. **New Request** 생성
2. **Method**: `GET`
3. **URL**: `http://localhost:8080/api/notifications/stream`
4. **Authorization** 탭:
   - Type: `Bearer Token`
   - Token: `{1단계에서 받은 accessToken}`
5. **Headers** 탭에 추가:
   - `Accept`: `text/event-stream`
6. **Send** 클릭

#### 방법 2: Postman Console로 확인
- **View** → **Show Postman Console** 활성화
- SSE 메시지가 Console에 실시간으로 표시됩니다

### 예상 응답 (SSE 스트림)
```
data: {"id":1,"currentStock":3,"thresholdValue":2,"message":"재고가 3개로 얼마 남지 않았어요","isRead":false,"createdAt":"2025-11-04T10:30:00","userId":10,"productId":"0ff5617a-e130-4deb-8568-6cc5d4cbd758"}

event: notification
data: {"id":2,"currentStock":2,"thresholdValue":2,"message":"재고가 2개로 얼마 남지 않았어요","isRead":false,"createdAt":"2025-11-04T10:30:05","userId":10,"productId":"0ff5617a-e130-4deb-8568-6cc5d4cbd758"}
```

**⚠️ 주의**: SSE 연결은 계속 열려있으므로, 별도의 탭/창에서 테스트하세요.

---

## 4단계: User B 로그인 (또는 다른 사용자)

### Request 설정
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/auth/login`
- **Headers**: 
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "email": "user123@test.com",
  "password": "1234"
}
```

### 응답에서 `accessToken` 복사

---

## 5단계: User B 제품 구매 (임계값 이하로 재고 감소)

### Request 설정
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/products/purchase`
- **Headers**: 
  - `Content-Type: application/json`
  - `Authorization: Bearer {4단계에서 받은 accessToken}`
- **Body** (raw JSON):
```json
{
  "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758",
  "quantity": 4
}
```

### 임계값 테스트 시나리오
- **초기 재고**: 5개
- **임계값**: 2개
- **구매 수량**: 4개
- **구매 후 재고**: 1개
- **결과**: 1 <= 2 → **알림 발송 ✓**

### 응답 예시
```json
{
  "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758",
  "quantity": 4,
  "remainingStock": 1,
  "stockThresholdReached": true,
  "message": "재고가 1개로 얼마 남지 않았어요"
}
```

---

## 6단계: User A SSE 메시지 확인

### 확인 방법

#### 방법 1: Postman Console
- 3단계에서 열어둔 SSE 연결 창/탭 확인
- 실시간으로 알림 메시지가 수신되는지 확인

#### 예상 SSE 메시지
```
event: notification
data: {"id":1,"currentStock":1,"thresholdValue":2,"message":"재고가 1개로 얼마 남지 않았어요","isRead":false,"createdAt":"2025-11-04T10:31:00","userId":10,"productId":"0ff5617a-e130-4deb-8568-6cc5d4cbd758"}
```

#### 방법 2: 알림 조회 API
- **Method**: `GET`
- **URL**: `http://localhost:8080/api/notifications`
- **Headers**: 
  - `Authorization: Bearer {User A의 accessToken}`
- **응답**: 알림 목록 확인

---

## 📊 전체 테스트 플로우 요약

```
┌─────────┐
│ User A  │
└────┬────┘
     │
     ├─ 1. 로그인 → accessToken 획득
     │
     ├─ 2. 장바구니에 제품 추가
     │
     └─ 3. SSE 연결 (실시간 알림 대기)
        │
        │
┌───────┴────────┐
│                │
│ User B 구매    │
│                │
│ 4. 로그인      │
│ 5. 제품 구매   │ → 재고 임계값 이하
│                │
│ Kafka 이벤트   │
│ 발행           │
│                │
│ Kafka Consumer │
│ 알림 생성      │
│                │
│ SSE 전송       │ ──→ User A에게 실시간 알림 도착 ✓
│                │
└────────────────┘
```

---

## 🔍 테스트 체크리스트

### ✅ 1단계: 로그인
- [ ] User A 로그인 성공
- [ ] `accessToken` 획득

### ✅ 2단계: 장바구니 추가
- [ ] 제품이 장바구니에 추가됨
- [ ] `cartId`, `cartItemId` 확인

### ✅ 3단계: SSE 연결
- [ ] SSE 연결 성공 (200 OK)
- [ ] Postman Console에서 연결 유지 확인

### ✅ 4-5단계: 제품 구매
- [ ] User B 로그인 성공
- [ ] 제품 구매 성공
- [ ] 재고가 임계값 이하로 감소
- [ ] `stockThresholdReached: true` 확인

### ✅ 6단계: 실시간 알림 확인
- [ ] User A의 SSE 연결에서 알림 메시지 수신
- [ ] 알림 메시지 내용 확인: "재고가 X개로 얼마 남지 않았어요"

---

## 🐛 문제 해결

### SSE 연결이 즉시 종료되는 경우
1. **토큰 확인**: `accessToken`이 유효한지 확인
2. **토큰 만료**: 토큰이 만료되었으면 다시 로그인
3. **Authorization 헤더**: `Bearer {token}` 형식 확인

### 알림이 오지 않는 경우
1. **Kafka 실행 확인**: `docker ps | grep kafka`
2. **장바구니 확인**: User A의 장바구니에 해당 제품이 있는지 확인
3. **재고 확인**: 구매 후 재고가 임계값 이하인지 확인
4. **로그 확인**: 애플리케이션 로그에서 `[KAFKA_CONSUME_SUCCESS]` 확인

### Postman에서 SSE가 보이지 않는 경우
1. **Postman Console 확인**: View → Show Postman Console
2. **별도 탭 사용**: SSE 연결은 별도 탭에서 열어두기
3. **Response 탭 확인**: Postman의 Response 탭에서 실시간 메시지 확인

---

## 📝 예시 제품 데이터

### 테스트용 제품 ID (임계값 설정됨)

| 제품명 | ID | 재고 | 임계값 |
|--------|----|------|--------|
| 콘서트 VIP 티켓 | `0ff5617a-e130-4deb-8568-6cc5d4cbd758` | 5 | 2 |
| iPhone 15 Pro | `47d1cb5a-545e-4695-a04c-d04b6af07256` | 50 | 10 |
| 에어팟 Pro 2세대 | `197f442e-e894-478d-ac6f-7e464547ad11` | 80 | 15 |
| 맥북 프로 16인치 | `249f2bae-e362-4eef-bf7b-526a44d71d0e` | 30 | 5 |

### 제품 ID 확인 SQL
```sql
SELECT id, name, stock, threshold_value 
FROM product 
WHERE is_active = true 
AND threshold_value IS NOT NULL 
ORDER BY name;
```

---

## 🎯 빠른 테스트 시나리오

### 시나리오: 콘서트 티켓 재고 부족 알림

1. **User A 로그인**
   ```json
   POST /api/auth/login
   {"email": "admin@test.com", "password": "1234"}
   ```

2. **User A 장바구니 추가**
   ```json
   POST /api/cart/add
   Authorization: Bearer {token}
   {"productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758", "quantity": 2}
   ```

3. **User A SSE 연결** (별도 탭에서 열어두기)
   ```
   GET /api/notifications/stream
   Authorization: Bearer {token}
   ```

4. **User B 로그인**
   ```json
   POST /api/auth/login
   {"email": "user123@test.com", "password": "1234"}
   ```

5. **User B 제품 구매** (재고 5 → 1, 임계값 2)
   ```json
   POST /api/products/purchase
   Authorization: Bearer {User B token}
   {"productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758", "quantity": 4}
   ```

6. **User A SSE에서 알림 확인**
   ```
   event: notification
   data: {"message":"재고가 1개로 얼마 남지 않았어요",...}
   ```

---

## 📌 참고사항

- **SSE 연결**: 한 번 연결하면 계속 열려있습니다. 새 알림이 오면 자동으로 수신됩니다.
- **Kafka 비동기 처리**: 구매 API는 즉시 반환되며, 알림은 Kafka를 통해 비동기로 처리됩니다.
- **성능 측정**: 애플리케이션 로그에서 `[KAFKA_PUBLISH_COMPLETE]`, `[KAFKA_CONSUME_SUCCESS]`, `[SSE_SEND_SUCCESS]` 로그를 확인하여 성능을 측정할 수 있습니다.

---

## ✅ 테스트 완료 확인

모든 단계를 완료하면:
- ✅ User A의 SSE 연결에서 실시간 알림 수신
- ✅ 알림 메시지: "재고가 X개로 얼마 남지 않았어요"
- ✅ Kafka를 통한 비동기 처리로 빠른 응답 시간
- ✅ 장바구니에 해당 제품이 있는 모든 사용자에게 알림 발송

**축하합니다! 실시간 SSE 알림 시스템이 정상 작동합니다! 🎉**
