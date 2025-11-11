# Toss Payments 결제 시스템 가이드

이 문서는 Toss Payments를 이용한 결제 프로세스 구현 및 테스트 방법을 설명합니다.

## 목차
- [시스템 개요](#시스템-개요)
- [결제 프로세스 플로우](#결제-프로세스-플로우)
- [API 엔드포인트](#api-엔드포인트)
- [테스트 방법](#테스트-방법)
- [에러 처리](#에러-처리)

---

## 시스템 개요

### 아키텍처 구성
```
┌─────────────┐      ┌─────────────┐      ┌──────────────────┐
│   Client    │─────▶│   Backend   │─────▶│ Toss Payments   │
│  (브라우저)  │      │   Server    │      │      API        │
└─────────────┘      └─────────────┘      └──────────────────┘
```

### 주요 컴포넌트
- **Payment Domain**: 결제 도메인 모델 (Payment 엔티티, VO, Repository)
- **PaymentApplicationService**: 결제 비즈니스 로직 처리
- **TossPaymentClient**: Toss Payments API 호출
- **PaymentController**: REST API 엔드포인트

### 데이터 모델 관계
```
Order 1:1 Payment
Payment → PaymentStatus (PENDING, READY, DONE, CANCELED, FAILED, ...)
Payment → PaymentMethod (CARD, VIRTUAL_ACCOUNT, TRANSFER, ...)
```

---

## 결제 프로세스 플로우

### 1. 주문 생성 시 자동 결제 생성
```
POST /api/orders
└─▶ Order 생성
    └─▶ Payment 자동 생성 (PENDING 상태)
```

**상태 변화:**
- Order: `PENDING`
- Payment: `PENDING`

**처리 내역:**
- 장바구니 상품 확인
- 재고 확인 (차감 X)
- 쿠폰 할인 적용
- Order 저장
- Payment 자동 생성 및 연결

---

### 2. 결제 진행 (클라이언트)

클라이언트는 Toss Payments 위젯을 사용하여 결제를 진행합니다.

#### 2-1. 결제 위젯 초기화
```javascript
// Toss Payments SDK 로드
const clientKey = 'test_ck_D5GePWvyJnrK0W0k6q8gLzN97Eoq';
const tossPayments = TossPayments(clientKey);

// 결제 위젯 생성
const payment = tossPayments.payment({
  amount: order.totalAmount,
  orderId: order.id,
  orderName: payment.orderName,
  customerName: user.name,
  successUrl: 'http://localhost:3000/payment/success',
  failUrl: 'http://localhost:3000/payment/fail',
});
```

#### 2-2. 결제 수단 선택
```javascript
// 카드 결제
payment.requestPayment('카드');

// 간편결제
payment.requestPayment('간편결제');

// 가상계좌
payment.requestPayment('가상계좌');
```

---

### 3. 결제 승인 (백엔드)

결제 성공 시 클라이언트는 `successUrl`로 리다이렉트되며, 쿼리 파라미터로 다음 정보를 받습니다:
- `paymentKey`: Toss가 발급한 결제 고유키
- `orderId`: 주문 ID
- `amount`: 결제 금액

클라이언트는 이 정보를 백엔드로 전송하여 결제 승인을 요청합니다.

```
POST /api/payments/confirm
└─▶ Toss API 결제 승인 요청
    ├─▶ Payment 상태 변경 (PENDING → DONE)
    ├─▶ Order 상태 변경 (PENDING → CONFIRMED)
    └─▶ Product 재고 차감
```

**상태 변화:**
- Order: `PENDING` → `CONFIRMED`
- Payment: `PENDING` → `DONE`
- Product: 재고 차감

**처리 내역:**
1. Payment 조회
2. 금액 검증
3. Toss Payments API 승인 요청 (`POST /v1/payments/confirm`)
4. Payment 승인 처리 (paymentKey, transactionId 저장)
5. Order 상태 변경
6. Product 재고 차감
7. 승인 완료

---

### 4. 결제 취소

```
POST /api/payments/cancel
└─▶ Toss API 결제 취소 요청
    ├─▶ Payment 상태 변경 (DONE → CANCELED)
    ├─▶ Order 상태 변경 (CONFIRMED → CANCELLED)
    └─▶ Product 재고 복구
```

**상태 변화:**
- Order: `CONFIRMED` → `CANCELLED`
- Payment: `DONE` → `CANCELED`
- Product: 재고 복구

**처리 내역:**
1. Payment 조회
2. Toss Payments API 취소 요청 (`POST /v1/payments/{paymentKey}/cancel`)
3. Payment 취소 처리
4. Order 상태 변경
5. Product 재고 복구
6. 취소 완료

---

## API 엔드포인트

### 1. 주문 생성 (Payment 자동 생성)
**Endpoint:** `POST /api/orders`

**Request:**
```json
{
  "couponId": 1
}
```

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 123,
  "subTotal": 50000,
  "discountAmount": 5000,
  "totalAmount": 45000,
  "status": "PENDING",
  "createdAt": "2025-11-06T12:00:00",
  "orderItems": [...]
}
```

**설명:**
- 장바구니 상품으로 Order 생성
- Payment 자동 생성 (PENDING 상태)
- 재고는 아직 차감되지 않음

---

### 2. 결제 조회 (주문 기반)
**Endpoint:** `GET /api/payments/order/{orderId}`

**Response:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 123,
  "amount": 45000,
  "status": "PENDING",
  "method": "CARD",
  "orderName": "맥북 프로 외 2건",
  "createdAt": "2025-11-06T12:00:00"
}
```

**설명:**
- 주문에 연결된 Payment 정보 조회
- 클라이언트는 이 정보로 결제 위젯 초기화

---

### 3-A. 테스트용 결제 승인 (추천)
**Endpoint:** `POST /api/payments/confirm/test`

**Request:**
```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 123,
  "paymentKey": "test_abc123...",
  "transactionId": "tx_test_xyz789...",
  "amount": 45000,
  "status": "DONE",
  "method": "CARD",
  "orderName": "맥북 프로 외 2건",
  "approvedAt": "2025-11-06T12:05:00",
  "createdAt": "2025-11-06T12:00:00"
}
```

**설명:**
- Toss Payments API를 호출하지 않고 결제 승인 테스트
- Payment 상태 변경 (PENDING → DONE)
- Order 상태 변경 (PENDING → CONFIRMED)
- Product 재고 차감
- **TICKET 카테고리 상품 자동 처리:**
    - 상품이 TICKET 카테고리인 경우 Raffle 티켓 자동 생성
    - 응모 가능 여부 검증 (기간, 상태, 한도)
    - 수량만큼 raffle_tickets 테이블에 레코드 생성

---

### 3. 결제 승인 (실제 Toss API 사용)
**Endpoint:** `POST /api/payments/confirm`

**Request:**
```json
{
  "paymentKey": "tviva20241106120000abcdefgh",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "amount": 45000
}
```

**Response:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 123,
  "paymentKey": "tviva20241106120000abcdefgh",
  "transactionId": "tx_20241106120000xyz",
  "amount": 45000,
  "status": "DONE",
  "method": "CARD",
  "orderName": "맥북 프로 외 2건",
  "approvedAt": "2025-11-06T12:05:00",
  "createdAt": "2025-11-06T12:00:00"
}
```

**설명:**
- Toss Payments API로 결제 승인 요청
- Payment 상태 변경 (PENDING → DONE)
- Order 상태 변경 (PENDING → CONFIRMED)
- Product 재고 차감

---

### 4. 결제 취소
**Endpoint:** `POST /api/payments/cancel`

**Request:**
```json
{
  "paymentId": "660e8400-e29b-41d4-a716-446655440001",
  "cancelReason": "고객 단순 변심"
}
```

**Response:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "CANCELED",
  "canceledAt": "2025-11-06T12:10:00",
  ...
}
```

**설명:**
- Toss Payments API로 결제 취소 요청
- Payment 상태 변경 (DONE → CANCELED)
- Order 상태 변경 (CONFIRMED → CANCELLED)
- Product 재고 복구

---

### 5. 내 결제 목록 조회
**Endpoint:** `GET /api/payments/my`

**Response:**
```json
[
  {
    "id": "660e8400-e29b-41d4-a716-446655440001",
    "orderId": "550e8400-e29b-41d4-a716-446655440000",
    "amount": 45000,
    "status": "DONE",
    "method": "CARD",
    "orderName": "맥북 프로 외 2건",
    "approvedAt": "2025-11-06T12:05:00",
    "createdAt": "2025-11-06T12:00:00"
  }
]
```

---

## 테스트 방법

### 1. 환경 설정

**application-dev.yml 확인:**
```yaml
payment:
  toss:
    secret-key: test_gsk_docs_OaPz8L5KdmQXkzRz3y47BMw6
    api-url: https://api.tosspayments.com
```

> ⚠️ **주의:** `test_gsk_docs_OaPz8L5KdmQXkzRz3y47BMw6`는 Toss Payments 공식 테스트 시크릿 키입니다.

---

### 2. Postman으로 API 테스트

#### Step 1: 로그인 및 토큰 발급
```http
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "username": "testuser",
  "password": "password123"
}
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "..."
}
```

**이후 모든 요청에 Authorization 헤더 추가:**
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

#### Step 2: 장바구니에 상품 추가
```http
POST http://localhost:8080/api/cart/items
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "productId": "product-uuid-1",
  "quantity": 2
}
```

---

#### Step 3: 주문 생성 (Payment 자동 생성)
```http
POST http://localhost:8080/api/orders
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "couponId": null
}
```

**Response에서 `orderId` 저장:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "totalAmount": 45000,
  "status": "PENDING",
  ...
}
```

---

#### Step 4: Payment 조회
```http
GET http://localhost:8080/api/payments/order/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer {accessToken}
```

**Response에서 `paymentId`, `amount`, `orderName` 확인:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "amount": 45000,
  "orderName": "맥북 프로 외 2건",
  "status": "PENDING"
}
```

---

#### Step 5-A: 테스트용 결제 승인 (추천)

**간단한 테스트를 위해 테스트 전용 API를 사용하세요:**

```http
POST http://localhost:8080/api/payments/confirm/test
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "orderId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**성공 Response:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "status": "DONE",
  "approvedAt": "2025-11-06T12:05:00",
  ...
}
```

**결과 확인:**
- Payment 상태: `DONE`
- Order 상태: `CONFIRMED`
- Product 재고: 차감됨

---

#### Step 6: 결제 취소
```http
POST http://localhost:8080/api/payments/cancel
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "paymentId": "660e8400-e29b-41d4-a716-446655440001",
  "cancelReason": "테스트 취소"
}
```

**결과 확인:**
- Payment 상태: `CANCELED`
- Order 상태: `CANCELLED`
- Product 재고: 복구됨

---

### 3. Toss Payments 테스트 카드

Toss Payments는 테스트 환경에서 다음 카드 정보를 사용할 수 있습니다:

| 카드사 | 카드 번호 | CVC | 유효기간 |
|--------|-----------|-----|----------|
| 신한카드 | 5480-6077-0157-8284 | 123 | 26/04 |
| KB국민카드 | 9430-0412-3456-7890 | 123 | 26/04 |
| 현대카드 | 9490-0412-3456-7890 | 123 | 26/04 |

**테스트 결제 시나리오:**
- 결제 성공: 위 카드 번호 사용
- 결제 실패: 잘못된 카드 번호 입력 (예: `1234-1234-1234-1234`)
- 한도 초과: 100,000원 이상 결제 시도

---

### 4. 통합 테스트 플로우

#### A. 테스트 API를 이용한 간단한 플로우 (추천)

```
1. 회원가입/로그인
   └─▶ AccessToken 획득

2. 상품 조회
   └─▶ 구매할 상품 선택 (GENERAL 또는 TICKET 카테고리)

3. 장바구니 추가
   └─▶ 여러 상품 추가 가능

4. 주문 생성
   ├─▶ Order 생성 (PENDING)
   └─▶ Payment 자동 생성 (PENDING)

5. 테스트용 결제 승인 (POST /api/payments/confirm/test)
   ├─▶ Payment 상태 변경 (DONE)
   ├─▶ Order 상태 변경 (CONFIRMED)
   ├─▶ 재고 차감
   └─▶ [TICKET 상품인 경우] Raffle 티켓 자동 생성
       ├─▶ Raffle 조회 (raffleProductId로)
       ├─▶ 응모 가능 여부 검증
       ├─▶ 응모 한도 검증
       └─▶ raffle_tickets 테이블에 티켓 생성

6. (선택) 결제 취소
   ├─▶ Payment 상태 변경 (CANCELED)
   ├─▶ Order 상태 변경 (CANCELLED)
   └─▶ 재고 복구
```

#### B. 실제 Toss API를 이용한 플로우

```
1. 회원가입/로그인
   └─▶ AccessToken 획득

2. 상품 조회
   └─▶ 구매할 상품 선택

3. 장바구니 추가
   └─▶ 여러 상품 추가 가능

4. 주문 생성
   ├─▶ Order 생성 (PENDING)
   └─▶ Payment 자동 생성 (PENDING)

5. (클라이언트) Toss 결제 위젯
   ├─▶ 테스트 카드로 결제
   └─▶ successUrl로 리다이렉트

6. 결제 승인 (POST /api/payments/confirm)
   ├─▶ Toss API 호출
   ├─▶ Payment 상태 변경 (DONE)
   ├─▶ Order 상태 변경 (CONFIRMED)
   ├─▶ 재고 차감
   └─▶ [TICKET 상품인 경우] Raffle 티켓 자동 생성

7. (선택) 결제 취소
   ├─▶ Payment 상태 변경 (CANCELED)
   ├─▶ Order 상태 변경 (CANCELLED)
   └─▶ 재고 복구
```

---

## 에러 처리

### 1. 결제 승인 실패

**원인:**
- 금액 불일치
- 이미 승인된 결제
- Toss API 오류

**처리:**
- Payment 상태: `FAILED`
- `failureCode`, `failureMessage` 저장
- Order 상태: `PENDING` 유지

**Response:**
```json
{
  "error": "결제 승인에 실패했습니다: 금액이 일치하지 않습니다.",
  "failureCode": "AMOUNT_MISMATCH"
}
```

---

### 2. 재고 부족

**원인:**
- 결제 승인 시점에 재고가 이미 소진됨

**처리:**
- Payment 상태: `FAILED`
- Order 상태: `PENDING` 유지

**Response:**
```json
{
  "error": "재고가 부족합니다. 상품: 맥북 프로, 요청: 2, 재고: 1"
}
```

---

### 3. 결제 취소 실패

**원인:**
- 이미 취소된 결제
- 취소 불가능한 상태 (부분 취소 등)
- Toss API 오류

**Response:**
```json
{
  "error": "결제 취소에 실패했습니다: 이미 취소된 결제입니다."
}
```

---

## 로깅

시스템은 다음 로그를 출력합니다:

### 기본 결제 로그
```
[PAYMENT_AUTO_CREATED] Payment automatically created - PaymentId: xxx, OrderId: yyy, Amount: 45000
[TOSS_API_REQUEST] Confirm payment - PaymentKey: xxx, OrderId: yyy, Amount: 45000
[TOSS_API_SUCCESS] Payment confirmed - PaymentKey: xxx
[PAYMENT_CONFIRM_SUCCESS] Payment confirmed - PaymentId: xxx, OrderId: yyy
[TEST_PAYMENT_CONFIRM] Test payment confirm start - OrderId: xxx
[TEST_PAYMENT_CONFIRM_SUCCESS] Test payment confirmed - PaymentId: xxx, OrderId: yyy
[STOCK_REDUCE] Product stock reduced - ProductId: xxx, Quantity: 2, Remaining: 8
[PAYMENT_CANCEL_SUCCESS] Payment cancelled - PaymentId: xxx, Reason: 고객 단순 변심
[STOCK_RESTORE] Product stock restored - ProductId: xxx, Quantity: 2, Current: 10
```

### TICKET 상품 Raffle 티켓 생성 로그
```
[TICKET_PRODUCT_PROCESS] TICKET product detected - ProductId: xxx, UserId: 123, Quantity: 2
[RAFFLE_TICKET_CREATED] Raffle ticket created - RaffleId: 1, UserId: 123, Count: 1/2
[RAFFLE_TICKET_CREATED] Raffle ticket created - RaffleId: 1, UserId: 123, Count: 2/2
[TICKET_PRODUCT_PROCESS_SUCCESS] All tickets created - ProductId: xxx, RaffleId: 1, UserId: 123, TotalCount: 2
```

### 에러 로그
```
[RAFFLE_TICKET_FAILED] Failed to create raffle ticket - RaffleId: 1, UserId: 123, Count: 1/2
[PAYMENT_CONFIRM_FAILED] Payment failed - OrderId: xxx, Error: ...
```

---

## 추가 참고 자료

- [Toss Payments 공식 문서](https://docs.tosspayments.com/)
- [Toss Payments API Reference](https://docs.tosspayments.com/reference)
- [결제 위젯 가이드](https://docs.tosspayments.com/guides/v2/payment-widget/overview)

---

## 문의

결제 시스템 관련 문의사항은 개발팀으로 연락주세요.
