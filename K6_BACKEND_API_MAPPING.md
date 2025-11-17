# k6 로드 테스트 - 백엔드 API 매핑 문서

## 개요

이 문서는 실제 백엔드 API 구조를 분석한 후, k6 로드 테스트 스크립트를 재작성한 내용을 설명합니다.

**작성 일시**: 2025-11-13
**분석 범위**: ProductController, OrderController, PaymentController, CartController, AuthController

---

## 1. 주요 변경 사항

### 1.1 Product 도메인

#### 변경 전 (잘못된 API)
```
GET /v1/product/list (상품 목록)
GET /v1/product/1 (상품 상세)
GET /v1/product/category/1 (카테고리별)
GET /v1/product/search?keyword=test (검색)
GET /v1/product/list?page=1&size=20 (페이지네이션)
```

#### 변경 후 (실제 API)
```
GET /v1/product (상품 목록 with pagination)
  - 쿼리: ?page=0&size=20&sort=id,DESC
  - 응답: Page 객체 (content, number, size, totalElements, totalPages 포함)

GET /v1/product/{id} (상품 상세)
  - Path Variable: id는 UUID (예: 550e8400-e29b-41d4-a716-446655440000)
  - 응답: ProductResponse

GET /v1/product/search (조건 검색)
  - 쿼리: ProductSearchRequest를 ModelAttribute로 받음
  - 응답: Page 객체

POST /v1/product/purchase (제품 구매)
  - 요청: PurchaseProductRequest (productId UUID, quantity Integer) 또는 null (장바구니 전체 구매)
  - 응답: PurchaseProductResponse 또는 PurchaseCartResponse
```

#### k6 수정 사항
- `product/list` → `product` (페이지네이션 쿼리 추가)
- `product/1` → `product/{UUID}` (UUID 사용)
- JSON 응답에서 `data` → `content` 확인
- `product/category` 제거 (API에 없음)

**파일**: `k6/scripts/product-test.js`

---

### 1.2 Cart & Order 도메인

#### 변경 전 (잘못된 API)
```
POST /v1/order/create (주문 생성)
  - 요청: cartItems[], shippingAddress, paymentMethod

GET /v1/order/list (주문 목록)
GET /v1/order/1 (주문 상세)
POST /v1/order/cancel (주문 취소)
```

#### 변경 후 (실제 API)
```
GET /v1/cart (장바구니 조회)
  - 요청: 없음 (인증만 필요)
  - 응답: CartResponse (cartId Long, items[], totalItems, totalPrice, message)

POST /v1/cart/add (장바구니에 상품 추가)
  - 요청: AddToCartRequest { productId UUID, quantity Integer }
  - 응답: AddToCartResponse (cartId, cartItemId, productId, quantity, message)

PATCH /v1/cart/increase-quantity (장바구니 수량 증가)
  - 요청: UpdateCartQuantityRequest { productId UUID }
  - 응답: UpdateCartQuantityResponse

PATCH /v1/cart/decrease-quantity (장바구니 수량 감소)
  - 요청: UpdateCartQuantityRequest { productId UUID }
  - 응답: UpdateCartQuantityResponse

DELETE /v1/cart/remove (장바구니에서 제거)
  - 요청: RemoveCartItemsRequest { items[{ productId UUID, quantity Integer }] }

POST /v1/order (주문 생성)
  - 요청: CreateOrderRequest { couponId Long } (선택사항)
  - 응답: OrderResponse (orderId UUID, userId Long, subTotal, discountAmount, totalAmount, status, couponId, createdAt, orderItems[])
  - Status Code: 201 (Created)

GET /v1/order/{orderId} (주문 상세)
  - Path Variable: orderId는 UUID
  - 응답: OrderResponse
```

#### k6 수정 사항
- `POST /v1/order/create` → `POST /v1/order` (엔드포인트 변경)
- CreateOrderRequest에 `couponId` 필드만 존재 (cartItems, shippingAddress 제거)
- orderId는 String이 아닌 UUID 사용
- 응답 Status Code: 201 (Created)
- `GET /v1/order/list` 제거 (API에 없음)
- `POST /v1/order/cancel` 제거 (API에 없음)
- 장바구니 조회, 추가, 수량 관리 API 추가

**파일**: `k6/scripts/order-test.js`

---

### 1.3 Payment 도메인

#### 변경 전 (잘못된 API)
```
GET /v1/payment/info (결제 정보 조회)
POST /v1/payment/prepare (결제 준비)
POST /v1/payment/approve (결제 승인)
GET /v1/payment/history (결제 내역 조회)
POST /v1/payment/refund (환불)
```

#### 변경 후 (실제 API)
```
GET /v1/payment/my (내 결제 내역 조회)
  - 응답: List<PaymentResponse>

GET /v1/payment/{paymentId} (결제 상세 조회)
  - Path Variable: paymentId는 UUID
  - 응답: PaymentResponse

GET /v1/payment/order/{orderId} (주문별 결제 조회)
  - Path Variable: orderId는 UUID
  - 응답: PaymentResponse

POST /v1/payment/confirm (결제 승인 - 실제 Toss API 호출)
  - 요청: ConfirmPaymentRequest { paymentKey String, orderId UUID, amount Integer }
  - 응답: PaymentResponse

POST /v1/payment/confirm/test (테스트용 결제 승인 - Toss API 호출 없음)
  - 요청: ConfirmPaymentRequest { paymentKey String, orderId UUID, amount Integer }
  - 응답: PaymentResponse

POST /v1/payment/cancel (결제 취소 - ADMIN ONLY)
  - 요청: CancelPaymentRequest { paymentId UUID, cancelReason String }
  - 응답: PaymentResponse
  - 권한: @CheckPermission(roles = {"ADMIN"})
```

#### 요청/응답 구조
```
// ConfirmPaymentRequest
{
  "paymentKey": "tgen_20240101_abc123",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "amount": 100000
}

// PaymentResponse (주요 필드)
{
  "id": "UUID",
  "orderId": "UUID",
  "userId": 1,
  "paymentKey": "String",
  "amount": 100000,
  "status": "DONE | PENDING | FAILED",
  "method": "CARD | VIRTUAL_ACCOUNT",
  "orderName": "String",
  "customerName": "String",
  "requestedAt": "2024-01-01T12:00:00",
  "approvedAt": "2024-01-01T12:00:01",
  "createdAt": "2024-01-01T12:00:00"
}
```

#### k6 수정 사항
- `POST /v1/payment/approve` → `POST /v1/payment/confirm` (엔드포인트 변경)
- `POST /v1/payment/prepare` 제거 (API에 없음)
- `GET /v1/payment/history` → `GET /v1/payment/my` (엔드포인트 변경)
- `GET /v1/payment/info` 제거 (API에 없음)
- `POST /v1/payment/refund` → `POST /v1/payment/cancel` (엔드포인트 변경)
- ConfirmPaymentRequest 요청 구조 수정 (cardNumber, expiryDate, cvc 제거)
- paymentId, orderId는 UUID 사용
- 응답: 배열 vs 단일 객체 수정
- 테스트 결제와 실제 결제 분리 (`/confirm/test` vs `/confirm`)

**파일**: `k6/scripts/payment-test.js`

---

### 1.4 Authentication

#### 변경 전
```javascript
authToken = loginRes.json('data.accessToken');
```

#### 변경 후
```javascript
authToken = loginRes.json('accessToken');
```

**이유**: LoginResponse 구조 확인
```
LoginResponse {
  String accessToken,
  String refreshToken,
  String name,
  Role role
}
```

---

## 2. 데이터 타입 변경

| 항목 | 변경 전 | 변경 후 | 설명 |
|------|--------|--------|------|
| Product ID | String | UUID | `550e8400-e29b-41d4-a716-446655440000` 형식 |
| Order ID | String | UUID | UUID로 변경됨 |
| Payment ID | String | UUID | UUID로 변경됨 |
| Cart Item Quantity | String | Integer | 정수형으로 변경 |
| Coupon ID | - | Long (Optional) | CreateOrderRequest에서만 사용 |
| Page Parameter | `page=1` | `page=0` | 0-based indexing |

---

## 3. k6 테스트 스크립트 요약

### 3.1 product-test.js
- **VU**: 20 → 50 → 100 (최대)
- **시간**: 약 4분
- **테스트 항목**:
  1. 상품 목록 조회 (페이지네이션)
  2. 상품 상세 조회 (UUID)
  3. 상품 검색 (조건 검색)
  4. 페이지네이션 다양한 크기

### 3.2 order-test.js
- **VU**: 10 → 30 → 50 (최대)
- **시간**: 약 3분
- **테스트 항목**:
  1. 장바구니 조회
  2. 장바구니에 상품 추가 (UUID productId)
  3. 장바구니 수량 증가 (PATCH)
  4. 주문 생성 (couponId만 전송)

### 3.3 payment-test.js
- **VU**: 5 → 15 → 30 (최대)
- **시간**: 약 3분 30초
- **테스트 항목**:
  1. 내 결제 내역 조회 (GET /my)
  2. 주문별 결제 조회 (GET /order/{orderId})
  3. 결제 상세 조회 (GET /{paymentId})
  4. 테스트용 결제 승인 (POST /confirm/test)
  5. 결제 승인 (POST /confirm - 실제 Toss API)

### 3.4 integrated-test.js
- **VU**: 20 → 50 → 100 (최대)
- **시간**: 약 5분
- **테스트 항목**: Product → Order → Payment 전체 플로우

---

## 4. 주의사항

### 4.1 테스트 데이터
현재 테스트 스크립트에서 사용하는 UUID는 예시입니다. **반드시 실제 DB에 있는 데이터로 변경하세요.**

```javascript
// 변경 필요
const TEST_PRODUCT_ID = '550e8400-e29b-41d4-a716-446655440000';
const TEST_ORDER_ID = '550e8400-e29b-41d4-a716-446655440001';
const TEST_PAYMENT_ID = '550e8400-e29b-41d4-a716-446655440002';
```

**올바른 UUID 찾는 방법**:
```bash
# 1. 데이터베이스에서 조회
SELECT id FROM product LIMIT 1;
SELECT id FROM "order" LIMIT 1;
SELECT id FROM payment LIMIT 1;

# 2. Swagger UI에서 실제 응답 확인
# http://localhost:8080/swagger-ui.html
```

### 4.2 인증
모든 API는 JWT 인증이 필요합니다. 테스트 사용자를 미리 생성하세요.

```bash
# 회원가입 예시
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user_1@test.com",
    "password": "password123",
    "name": "Test User 1"
  }'
```

### 4.3 응답 코드 확인
- 주문 생성: **201 (Created)** (200이 아님!)
- 다른 GET: **200**
- 오류: **400, 401, 404**

### 4.4 Payment 테스트
`/payment/confirm/test` 사용을 권장합니다. 실제 Toss Payments API 호출로 인한 비용 발생을 피하기 위함입니다.

---

## 5. 실행 방법

### 5.1 개별 도메인 테스트
```bash
# Product 테스트 (약 4분)
k6 run k6/scripts/product-test.js

# Order 테스트 (약 3분)
k6 run k6/scripts/order-test.js

# Payment 테스트 (약 3분 30초)
k6 run k6/scripts/payment-test.js
```

### 5.2 통합 테스트
```bash
# 전체 플로우 테스트 (약 5분)
k6 run k6/scripts/integrated-test.js
```

### 5.3 결과 저장
```bash
# JSON 파일로 저장
k6 run --out json=results.json k6/scripts/product-test.js

# Grafana로 실시간 모니터링
k6 run --out grafana k6/scripts/product-test.js
```

### 5.4 VU 및 기간 커스터마이징
```bash
# 100 VU로 60초 테스트
k6 run --vus 100 --duration 60s k6/scripts/product-test.js

# 반복 기반 테스트 (100회)
k6 run --iterations 100 k6/scripts/product-test.js
```

---

## 6. 성능 목표 (Thresholds)

### Product 도메인
```
p95 응답 시간 < 500ms
p99 응답 시간 < 1000ms
실패율 < 0.1%
```

### Order 도메인
```
p95 응답 시간 < 1000ms
p99 응답 시간 < 2000ms
실패율 < 0.1%
```

### Payment 도메인
```
p95 응답 시간 < 2000ms
p99 응답 시간 < 3000ms
실패율 < 0.05% (결제는 더 높은 신뢰도 요구)
```

---

## 7. 트러블슈팅

### 401 Unauthorized
- 로그인 실패: 테스트 사용자 존재 확인
- 토큰 파싱: `loginRes.json('accessToken')` 확인

### 404 Not Found
- 엔드포인트 경로 재확인 (예: `/v1/product` vs `/v1/product/list`)
- UUID 값이 실제 DB에 존재하는지 확인

### 201 vs 200
- 주문 생성 응답은 반드시 **201** 확인
- 다른 POST 요청도 응답 코드 확인

### Page 응답 파싱
Product 목록 조회 시 `r.json('content')` 사용 (`.data` 아님)

---

**마지막 업데이트**: 2025-11-13
**작성**: Claude Code
**검토 필요**: 실제 데이터 UUID 치환 후 테스트 실행
