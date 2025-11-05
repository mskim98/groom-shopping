# 장바구니 API Postman 테스팅 가이드

이 가이드는 장바구니 조회 API를 Postman으로 테스트하는 방법을 설명합니다.

---

## 📋 사전 준비사항

1. **Spring Boot 애플리케이션 실행 중**
2. **PostgreSQL 데이터베이스 실행 중** (Docker 컨테이너: `db`)
3. **로그인하여 accessToken 획득**

---

## 🔑 1단계: 로그인 및 토큰 획득

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

### ⚠️ 중요: `accessToken` 값을 복사해두세요! (이하 `{TOKEN}`으로 표기)

---

## 🛒 2단계: 장바구니에 제품 추가 (선택사항)

### 장바구니가 비어있는 경우를 위해 제품 추가

#### Request 설정
- **Method**: `POST`
- **URL**: `http://localhost:8080/api/cart/add`
- **Headers**: 
  - `Content-Type: application/json`
  - `Authorization: Bearer {TOKEN}`
- **Body** (raw JSON):
```json
{
  "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
  "quantity": 2
}
```

#### 응답 예시
```json
{
  "cartId": 4,
  "cartItemId": 1,
  "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
  "quantity": 2,
  "message": "장바구니에 추가되었습니다."
}
```

#### 여러 제품 추가 (선택사항)
다른 제품도 추가해보세요:
```json
{
  "productId": "197f442e-e894-478d-ac6f-7e464547ad11",
  "quantity": 1
}
```

---

## 📋 3단계: 장바구니 조회

### Request 설정
- **Method**: `GET`
- **URL**: `http://localhost:8080/api/cart`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`

### 응답 예시

#### 장바구니에 제품이 있는 경우
```json
{
  "cartId": 4,
  "items": [
    {
      "cartItemId": 1,
      "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
      "productName": "iPhone 15 Pro",
      "price": 1500000,
      "quantity": 2,
      "totalPrice": 3000000,
      "createdAt": "2025-11-04T10:30:00",
      "updatedAt": "2025-11-04T10:30:00"
    },
    {
      "cartItemId": 2,
      "productId": "197f442e-e894-478d-ac6f-7e464547ad11",
      "productName": "에어팟 Pro 2세대",
      "price": 350000,
      "quantity": 1,
      "totalPrice": 350000,
      "createdAt": "2025-11-04T10:31:00",
      "updatedAt": "2025-11-04T10:31:00"
    }
  ],
  "totalItems": 2,
  "totalPrice": 3350000,
  "message": "2개 제품이 장바구니에 담겨있습니다."
}
```

#### 장바구니가 비어있는 경우
```json
{
  "cartId": null,
  "items": [],
  "totalItems": 0,
  "totalPrice": 0,
  "message": "장바구니가 비어있습니다."
}
```

---

## 📊 응답 필드 설명

| 필드 | 타입 | 설명 |
|------|------|------|
| `cartId` | Long | 장바구니 ID (비어있으면 null) |
| `items` | List | 장바구니 항목 목록 |
| `totalItems` | Integer | 전체 항목 수 |
| `totalPrice` | Integer | 전체 금액 합계 (각 제품의 price × quantity 합) |
| `message` | String | 상태 메시지 |

### CartItemResponse 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `cartItemId` | Long | 장바구니 항목 ID |
| `productId` | UUID | 제품 ID |
| `productName` | String | 제품명 |
| `price` | Integer | 단가 |
| `quantity` | Integer | 수량 |
| `totalPrice` | Integer | 항목별 총액 (price × quantity) |
| `createdAt` | LocalDateTime | 추가된 시간 |
| `updatedAt` | LocalDateTime | 수정된 시간 |

---

## 🎯 전체 테스트 시나리오

### 시나리오: 장바구니 조회 전체 플로우

1. **로그인**
   ```http
   POST /api/auth/login
   {"email": "admin@test.com", "password": "1234"}
   ```
   → `accessToken` 저장

2. **장바구니 조회 (초기 상태)**
   ```http
   GET /api/cart
   Authorization: Bearer {TOKEN}
   ```
   → 빈 장바구니 확인

3. **제품 1 추가**
   ```http
   POST /api/cart/add
   Authorization: Bearer {TOKEN}
   {"productId": "47d1cb5a-545e-4695-a04c-d04b6af07256", "quantity": 2}
   ```

4. **제품 2 추가**
   ```http
   POST /api/cart/add
   Authorization: Bearer {TOKEN}
   {"productId": "197f442e-e894-478d-ac6f-7e464547ad11", "quantity": 1}
   ```

5. **장바구니 조회 (최종 확인)**
   ```http
   GET /api/cart
   Authorization: Bearer {TOKEN}
   ```
   → 2개 제품이 담긴 장바구니 확인

---

## 🔍 테스트 체크리스트

- [ ] 로그인 성공 및 토큰 획득
- [ ] 빈 장바구니 조회 성공 (초기 상태)
- [ ] 제품 추가 성공
- [ ] 장바구니 조회 시 추가한 제품 확인
- [ ] `totalItems` 값이 올바른지 확인
- [ ] `totalPrice` 계산이 올바른지 확인 (price × quantity 합계)
- [ ] 여러 제품 추가 후 조회 시 모든 제품이 표시되는지 확인

---

## 🐛 문제 해결

### 장바구니가 비어있다고 나오는 경우
1. **제품 추가 확인**: 장바구니에 제품을 추가했는지 확인
2. **사용자 확인**: 다른 사용자로 로그인했는지 확인
3. **토큰 확인**: 올바른 사용자의 토큰을 사용하고 있는지 확인

### 제품 정보가 표시되지 않는 경우
1. **제품 ID 확인**: 제품이 실제로 존재하는지 확인
   ```bash
   docker exec -it db psql -U dev -d shopping_db_dev -c "SELECT id, name FROM product WHERE id = '제품ID'::uuid;"
   ```
2. **제품 활성화 확인**: `is_active = true`인지 확인

### totalPrice 계산이 잘못된 경우
- `totalPrice`는 각 항목의 `price × quantity`의 합계입니다
- 예: iPhone 1,500,000원 × 2개 + 에어팟 350,000원 × 1개 = 3,350,000원

---

## 🗑️ 4단계: 장바구니에서 제품 수량 줄이기 또는 제거

### Request 설정
- **Method**: `DELETE`
- **URL**: `http://localhost:8080/api/cart/remove`
- **Headers**: 
  - `Content-Type: application/json`
  - `Authorization: Bearer {TOKEN}`
- **Body** (raw JSON):

#### 제품 수량 줄이기 (부분 제거)
```json
{
  "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
  "quantity": 1
}
```
- 장바구니에 2개가 있으면 → 1개 남음
- 장바구니에 1개가 있으면 → 완전히 제거됨

#### 제품 완전히 제거 (수량만큼 제거)
```json
{
  "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
  "quantity": 2
}
```
- 장바구니에 2개가 있으면 → 완전히 제거됨

### 요청 규칙
- ✅ `productId`: 제품 ID (필수)
- ✅ `quantity`: 제거할 수량 (1 이상, 필수)
- ✅ 장바구니에 있는 제품만 제거 가능
- ✅ 제거할 수량은 장바구니 수량보다 많을 수 없음

### 응답 예시

#### 부분 제거 (수량이 남는 경우)
```json
{
  "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
  "removedQuantity": 1,
  "remainingQuantity": 1,
  "isRemoved": false,
  "message": "제품 수량이 1개로 감소했습니다. (제거 수량: 1)"
}
```

#### 완전 제거 (수량이 0이 되는 경우)
```json
{
  "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
  "removedQuantity": 2,
  "remainingQuantity": 0,
  "isRemoved": true,
  "message": "제품이 장바구니에서 완전히 제거되었습니다. (제거 수량: 2)"
}
```

#### 에러 응답 (제품이 없는 경우)
```json
{
  "error": "장바구니에 해당 제품이 없습니다."
}
```

#### 에러 응답 (수량이 부족한 경우)
```json
{
  "error": "제거할 수량(3)이 장바구니 수량(2)보다 많습니다."
}
```

---

## 📝 API 엔드포인트 요약

| 기능 | Method | Endpoint | 설명 |
|------|--------|----------|------|
| 장바구니 조회 | `GET` | `/api/cart` | 사용자의 장바구니 전체 조회 |
| 장바구니 추가 | `POST` | `/api/cart/add` | 장바구니에 제품 추가 |
| 장바구니 제거 | `DELETE` | `/api/cart/remove` | 장바구니에서 제품 수량 줄이기 또는 제거 |

---

## 🎯 전체 테스트 시나리오 (제거 포함)

### 시나리오: 장바구니 전체 플로우

1. **로그인**
   ```http
   POST /api/auth/login
   {"email": "admin@test.com", "password": "1234"}
   ```
   → `accessToken` 저장

2. **장바구니 조회 (초기 상태)**
   ```http
   GET /api/cart
   Authorization: Bearer {TOKEN}
   ```
   → 빈 장바구니 확인

3. **제품 1 추가**
   ```http
   POST /api/cart/add
   Authorization: Bearer {TOKEN}
   {"productId": "47d1cb5a-545e-4695-a04c-d04b6af07256", "quantity": 2}
   ```

4. **제품 2 추가**
   ```http
   POST /api/cart/add
   Authorization: Bearer {TOKEN}
   {"productId": "197f442e-e894-478d-ac6f-7e464547ad11", "quantity": 1}
   ```

5. **장바구니 조회 (제거 전)**
   ```http
   GET /api/cart
   Authorization: Bearer {TOKEN}
   ```
   → 2개 제품 확인

6. **제품 수량 줄이기 (부분 제거)**
   ```http
   DELETE /api/cart/remove
   Authorization: Bearer {TOKEN}
   {"productId": "47d1cb5a-545e-4695-a04c-d04b6af07256", "quantity": 1}
   ```
   → 응답: `{"message": "제품 수량이 1개로 감소했습니다.", "remainingQuantity": 1}`

7. **장바구니 조회 (수량 감소 확인)**
   ```http
   GET /api/cart
   Authorization: Bearer {TOKEN}
   ```
   → 수량이 감소한 것을 확인

8. **제품 완전히 제거 (나머지 수량 모두 제거)**
   ```http
   DELETE /api/cart/remove
   Authorization: Bearer {TOKEN}
   {"productId": "47d1cb5a-545e-4695-a04c-d04b6af07256", "quantity": 1}
   ```
   → 응답: `{"message": "제품이 장바구니에서 완전히 제거되었습니다.", "isRemoved": true}`

9. **장바구니 조회 (최종 확인)**
   ```http
   GET /api/cart
   Authorization: Bearer {TOKEN}
   ```
   → 빈 장바구니 확인

---

## 🔍 테스트 체크리스트 (제거 포함)

- [ ] 로그인 성공 및 토큰 획득
- [ ] 장바구니에 제품 추가
- [ ] 장바구니 조회 시 추가한 제품 확인
- [ ] 제품 수량 줄이기 성공 (부분 제거)
- [ ] 제거 후 장바구니 조회 시 수량이 감소한 것을 확인
- [ ] 제품 완전히 제거 성공 (수량 0)
- [ ] 존재하지 않는 제품 ID로 제거 시도 시 에러 반환 확인
- [ ] 장바구니 수량보다 많은 수량 제거 시도 시 에러 반환 확인
- [ ] 수량 1 미만으로 제거 시도 시 에러 반환 확인

---

## 🐛 문제 해결 (제거 관련)

### 제품이 제거되지 않는 경우
1. **제품 ID 확인**: 제거하려는 제품이 실제로 장바구니에 있는지 확인
2. **장바구니 조회**: 먼저 `GET /api/cart`로 장바구니에 있는 제품 ID와 수량 확인
3. **수량 확인**: 제거할 수량이 장바구니 수량보다 많지 않은지 확인
4. **UUID 형식 확인**: 제품 ID가 올바른 UUID 형식인지 확인
   ```json
   // ✅ 올바른 형식
   {
     "productId": "47d1cb5a-545e-4695-a04c-d04b6af07256",
     "quantity": 1
   }
   ```

### 에러 메시지 종류
- `"장바구니에 해당 제품이 없습니다."`: 장바구니에 없는 제품 ID
- `"제거할 수량(X)이 장바구니 수량(Y)보다 많습니다."`: 수량 초과
- `"제거할 수량은 1 이상이어야 합니다."`: 수량이 0 이하

---

## ✅ 테스트 완료 확인

모든 단계를 완료하면:
- ✅ 장바구니 조회 API가 정상 작동
- ✅ 장바구니 추가 API가 정상 작동
- ✅ 장바구니 제거 API가 정상 작동 (수량 줄이기 또는 완전 제거)
- ✅ 제품 정보가 올바르게 표시
- ✅ `totalPrice` 계산이 정확
- ✅ 빈 장바구니도 올바르게 처리

**축하합니다! 장바구니 전체 기능이 정상 작동합니다! 🎉**

