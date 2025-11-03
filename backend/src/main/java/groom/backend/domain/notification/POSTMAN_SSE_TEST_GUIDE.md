# Postman으로 실시간 SSE 알림 테스트 가이드

이 가이드는 Postman을 사용하여 로그인부터 실시간 SSE 알림 확인까지의 전체 과정을 테스트하는 방법을 설명합니다.

## 📋 사전 준비사항

1. **서버 실행 확인**
   - Spring Boot 애플리케이션이 `http://localhost:8080`에서 실행 중이어야 합니다
   - PostgreSQL 데이터베이스가 실행 중이어야 합니다
   - Redis가 실행 중이어야 합니다
   - Kafka가 실행 중이어야 합니다 (Docker: `docker ps | grep kafka`)

2. **테스트 제품 준비**
   - 제품 ID: `550e8400-e29b-41d4-a716-446655440000` (또는 다른 제품 ID)
   - 제품 재고를 100개 이상으로 설정 (임계값 테스트용)

---

## 🔐 1단계: A 유저 회원가입 및 로그인

### 1-1. A 유저 회원가입

**요청 설정:**
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/auth/signup`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "email": "userA@test.com",
  "password": "password123",
  "name": "유저A",
  "role": "ROLE_USER",
  "grade": "SILVER"
}
```



### 1-2. A 유저 로그인

**요청 설정:**
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/auth/login`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "email": "userA@test.com",
  "password": "password123"
}
```



**⚠️ 중요**: `accessToken`을 복사해서 저장해두세요! (이하 `A_TOKEN`으로 표기)

---

## 🔗 2단계: A 유저 SSE 연결 (Postman)

Postman에서 SSE를 테스트하는 방법은 두 가지가 있습니다:

### 방법 1: Postman의 SSE 지원 사용 (권장)

**Postman v10.14 이상**에서는 SSE를 직접 지원합니다.

**요청 설정:**
- **Method**: `GET`
- **URL**: `http://localhost:8080/api/notifications/stream`
- **Headers**:
  - `Authorization: Bearer {A_TOKEN}` (복사한 토큰 사용)
  - `Accept: text/event-stream`

**설정:**
1. Postman에서 요청 생성
2. `Send` 버튼 옆의 **`...`** 메뉴 클릭
3. **`Stream`** 또는 **`SSE`** 옵션 활성화
4. `Send` 클릭

**성공 확인:**
- 요청이 계속 열려있는 상태로 유지됩니다
- 응답 영역에 "Waiting for data..." 또는 연결 성공 메시지가 표시됩니다
- 애플리케이션 로그에 `[SSE_CONNECTION_CREATED]` 메시지가 보입니다

### 방법 2: curl 사용 (대안)

Postman에서 SSE 테스트가 어려운 경우, 별도 터미널에서 실행:

```bash
curl -N -H "Authorization: Bearer {A token}" \
  http://localhost:8080/api/notifications/stream
```

터미널이 응답을 기다리는 상태로 유지되면 연결 성공입니다.

---

## 🛒 3단계: A 유저 장바구니에 제품 추가

**요청 설정:**
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/cart/add`
- **Headers**:
  - `Authorization: Bearer {A_TOKEN}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 1
}
```

**예상 응답:**
```json
{
  "cartId": 1,
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 1,
  "message": "장바구니에 추가되었습니다."
}
```

**성공 확인:**
- 응답 코드: `200 OK`
- 애플리케이션 로그에 `[CART_ADD_SUCCESS]` 메시지 확인

---

## 👤 4단계: B 유저 회원가입 및 로그인

### 4-1. B 유저 회원가입

**요청 설정:**
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/auth/signup`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "email": "userB@test.com",
  "password": "password123",
  "name": "유저B",
  "role": "ROLE_USER",
  "grade": "SILVER"
}
```

### 4-2. B 유저 로그인

**요청 설정:**
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/auth/login`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "email": "userB@test.com",
  "password": "password123"
}
```

**⚠️ 중요**: B 유저의 `accessToken`도 복사해두세요! (이하 `B_TOKEN`으로 표기)

---

## 💰 5단계: B 유저가 제품 구매 (재고 임계값 도달)

### 제품 재고 확인 및 설정 (선택사항)

임계값 테스트를 위해 제품 재고를 적절히 설정해야 합니다.

**예시 시나리오:**
- 현재 재고: 100개
- 임계값: 10개
- 구매 수량: 91개
- 구매 후 재고: 9개 (임계값 이하)

**데이터베이스 직접 조회 (선택사항):**

⚠️ **PostgreSQL 대소문자 주의**: PostgreSQL은 따옴표 없는 식별자를 자동으로 소문자로 변환합니다. 
따옴표로 감싸면 대소문자를 구분합니다.

```bash
# 방법 1: 소문자 컬럼명 사용 (권장 - 실제 컬럼이 name, stock인 경우)
PGPASSWORD=dev123 psql -h localhost -U dev -d shopping_db_dev -c \
  "SELECT id, name, stock, threshold_value FROM product WHERE id = '550e8400-e29b-41d4-a716-446655440000'::uuid;"

# 방법 2: 기존 컬럼명 사용 (Field2, Field3 등이 아직 존재하는 경우)
PGPASSWORD=dev123 psql -h localhost -U dev -d shopping_db_dev -c \
  'SELECT id, "Field2" as name, "Field3" as stock, threshold_value FROM product WHERE id = '\''550e8400-e29b-41d4-a716-446655440000'\''::uuid;'
```

### B 유저 제품 구매 요청

**요청 설정:**
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/products/purchase`
- **Headers**:
  - `Authorization: Bearer {B_TOKEN}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 91
}
```

**예상 응답:**
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 91,
  "remainingStock": 9,
  "stockThresholdReached": true,
  "message": "재고가 9개로 얼마 남지 않았어요"
}
```

**성공 확인:**
- 응답 코드: `200 OK`
- `stockThresholdReached: true` 확인
- 애플리케이션 로그에 다음 메시지들 확인:
  - `[PURCHASE_API_START]`
  - `[PURCHASE_API_SUCCESS]`
  - `[KAFKA_PUBLISH_START]`
  - `[KAFKA_PUBLISH_COMPLETE]`

---

## 🔔 6단계: A 유저 SSE에서 실시간 알림 확인

### Postman SSE 연결 확인

**2단계에서 열어둔 SSE 연결**을 확인하세요.

**예상 SSE 메시지:**
```
event: notification
data: 재고가 9개로 얼마 남지 않았어요
```

### 애플리케이션 로그 확인

다음 로그 메시지들이 순서대로 나타나야 합니다:

1. **Kafka 이벤트 발행:**
   ```
   [KAFKA_PUBLISH_START] productId=...
   [KAFKA_PUBLISH_SUBMIT] productId=...
   [KAFKA_PUBLISH_COMPLETE] productId=...
   ```

2. **Kafka 이벤트 소비:**
   ```
   [KAFKA_CONSUME_START] productId=...
   [NOTIFICATION_SERVICE_START] productId=...
   [NOTIFICATION_QUERY_USERS] productId=..., userIdCount=1
   [NOTIFICATION_SENT] userId=1, notificationId=...
   [NOTIFICATION_SERVICE_COMPLETE] productId=...
   [KAFKA_CONSUME_SUCCESS] productId=...
   ```

3. **SSE 전송:**
   ```
   [SSE_SEND_SUCCESS] userId=1, notificationId=..., message=..., duration=Xms
   ```

---

## ⚡ 성능 측정 포인트

다음 로그에서 성능 개선을 확인할 수 있습니다:

### 1. API 응답 시간 (비동기 처리로 빠름)
```
[PURCHASE_API_SUCCESS] apiResponseTime=Xms
```
- **예상**: 수십 밀리초 이내
- **이유**: Kafka 발행은 비동기로 처리되어 응답을 블로킹하지 않음

### 2. Kafka 전송 시간
```
[KAFKA_PUBLISH_SUBMIT] submitDuration=Xms
[KAFKA_PUBLISH_COMPLETE] totalDuration=Xms, networkLatency=Xms
```
- **예상**: submit은 1ms 이하, 네트워크 레이턴시는 수 밀리초

### 3. End-to-End 시간 (이벤트 발생부터 알림 전송까지)
```
[KAFKA_CONSUME_SUCCESS] endToEndDuration=Xms
```
- **예상**: 수십 밀리초 ~ 수백 밀리초
- **이전 방식 (동기)**: 수 초 소요 (여러 사용자에게 순차적으로 알림 전송)
- **개선**: Kafka를 통한 비동기 처리로 **수 초 → 수십 밀리초**로 단축

### 4. SSE 전송 시간
```
[SSE_SEND_SUCCESS] duration=Xms
```
- **예상**: 1~10ms (네트워크 상태에 따라)

---

## 🔧 문제 해결

### SSE 연결이 즉시 종료되는 경우

1. **토큰 확인**
   - 토큰이 만료되지 않았는지 확인
   - 로그인을 다시 시도하여 새 토큰 발급

2. **인증 확인**
   - 애플리케이션 로그에서 `403 Forbidden` 또는 `401 Unauthorized` 확인
   - `NotificationController`에서 올바른 userId 추출 여부 확인

3. **서버 재시작**
   - 코드 변경 후 애플리케이션 재시작 확인

### 알림이 오지 않는 경우

1. **장바구니 확인**
   - A 유저가 해당 제품을 장바구니에 담았는지 확인
   ```sql
   -- PostgreSQL은 email을 소문자로 저장하므로 LOWER() 사용 권장
   SELECT * FROM cart 
   WHERE user_id = (SELECT id FROM users WHERE LOWER(email) = LOWER('userA@test.com'));
   ```

2. **Kafka 확인**
   - Kafka가 실행 중인지 확인
   - 토픽 생성 여부 확인
   ```bash
   docker exec -it kafka-container-name kafka-topics --list --bootstrap-server localhost:9092
   ```

3. **로그 확인**
   - `[NOTIFICATION_QUERY_USERS] userIdCount=0`인 경우, 장바구니에 제품이 없는 것
   - `[KAFKA_CONSUME_START]`가 없는 경우, Kafka 이벤트가 전달되지 않은 것

### 제품이 존재하지 않는 경우

1. **제품 생성** (SQL 직접 실행):

⚠️ **PostgreSQL 대소문자 주의**: 
- 따옴표 없는 컬럼명은 자동으로 소문자로 변환됩니다
- 대문자 컬럼명(Field2 등)을 사용하려면 큰따옴표로 감싸야 합니다

```sql
-- 방법 1: 새로운 컬럼명 사용 (name, stock 등 - 권장)
INSERT INTO product (
  id, name, description, price, stock, is_active, category, threshold_value, created_at, updated_at
)
VALUES (
  '550e8400-e29b-41d4-a716-446655440000'::uuid,
  '테스트 제품',
  '테스트용 제품 설명',
  10000,
  100,
  true,
  'ELECTRONICS',
  10,
  NOW(),
  NOW()
)
ON CONFLICT (id) DO UPDATE
SET 
  name = EXCLUDED.name,
  stock = EXCLUDED.stock,
  updated_at = NOW();

-- 방법 2: 기존 컬럼명 사용 (Field2, Field3 등 - 마이그레이션 전)
INSERT INTO product (
  id, "Field2", "Field5", price, "Field3", "Field", "Field4", threshold_value, created_at, updated_at
)
VALUES (
  '550e8400-e29b-41d4-a716-446655440000'::uuid,
  '테스트 제품',
  '테스트용 제품 설명',
  10000,
  100,
  true,
  'ELECTRONICS',
  10,
  NOW(),
  NOW()
)
ON CONFLICT (id) DO UPDATE
SET 
  "Field2" = EXCLUDED."Field2",
  "Field3" = EXCLUDED."Field3",
  updated_at = NOW();
```

**현재 스키마 확인:**
```bash
PGPASSWORD=dev123 psql -h localhost -U dev -d shopping_db_dev -c "\d product"
```

---

## 📝 테스트 체크리스트

- [ ] 서버 실행 확인 (Spring Boot, PostgreSQL, Redis, Kafka)
- [ ] A 유저 회원가입 성공
- [ ] A 유저 로그인 성공 (토큰 저장)
- [ ] A 유저 SSE 연결 성공 (연결 유지됨)
- [ ] A 유저 장바구니에 제품 추가 성공
- [ ] B 유저 회원가입 성공
- [ ] B 유저 로그인 성공 (토큰 저장)
- [ ] B 유저 제품 구매 성공 (임계값 도달)
- [ ] A 유저 SSE에서 알림 수신 확인
- [ ] 애플리케이션 로그에서 성능 메트릭 확인

---

## 🎯 완전 자동화된 테스트 (선택사항)

모든 단계를 자동화하려면 다음 스크립트를 참고하세요:

```bash
# A 유저 로그인
A_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"userA@test.com","password":"password123"}' \
  | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

# B 유저 로그인
B_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"userB@test.com","password":"password123"}' \
  | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

# A 유저 장바구니 추가
curl -X POST http://localhost:8080/api/cart/add \
  -H "Authorization: Bearer $A_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId":"550e8400-e29b-41d4-a716-446655440000","quantity":1}'

# A 유저 SSE 연결 (별도 터미널에서)
curl -N -H "Authorization: Bearer $A_TOKEN" \
  http://localhost:8080/api/notifications/stream

# B 유저 제품 구매 (다른 터미널에서)
curl -X POST http://localhost:8080/api/products/purchase \
  -H "Authorization: Bearer $B_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId":"550e8400-e29b-41d4-a716-446655440000","quantity":91}'
```

---

## 📚 참고

- **SSE 엔드포인트**: `GET /api/notifications/stream`
- **장바구니 추가**: `POST /api/cart/add`
- **제품 구매**: `POST /api/products/purchase`
- **로그인**: `POST /api/auth/login`
- **회원가입**: `POST /api/auth/signup`

**모든 API는 `/api` prefix를 사용합니다.**

---

**테스트 완료 후, 성능 로그를 확인하여 Kafka를 통한 비동기 처리로 인한 성능 개선을 확인하세요!** 🚀

