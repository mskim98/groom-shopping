# 결제 흐름 가이드 (Payment Flow Guide)

## 목차
1. [개요](#개요)
2. [테스트용 결제 승인 흐름](#테스트용-결제-승인-흐름)
3. [실제 결제 승인 흐름](#실제-결제-승인-흐름)
4. [데이터 흐름 예시](#데이터-흐름-예시)
5. [주요 컴포넌트](#주요-컴포넌트)

---

## 개요

이 문서는 Groom Shopping 백엔드의 결제 시스템 흐름을 설명합니다. 결제 시스템은 크게 두 가지 모드로 동작합니다:
- **테스트 모드**: Toss Payments API 호출 없이 결제 승인 (개발/테스트용)
- **실제 모드**: Toss Payments API를 호출하여 실제 결제 승인

---

## 테스트용 결제 승인 흐름

### API 엔드포인트
```
POST /payments/confirm/test
```

### 요청 데이터 예시
```json
{
  "orderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### 처리 흐름

#### 1. Controller Layer (PaymentController.java:83-99)
```java
@PostMapping("/confirm/test")
public ResponseEntity<PaymentResponse> confirmPaymentForTest(
    @AuthenticationPrincipal(expression = "user") User user,
    @RequestBody ConfirmPaymentRequest request)
```

**입력 데이터:**
- `user`: 인증된 사용자 정보 (JWT 토큰에서 추출)
- `request.orderId`: 결제할 주문 ID (UUID)

**처리:**
- 사용자 인증 확인
- Application Service 호출

**출력 데이터:**
```json
{
  "id": "payment-uuid",
  "orderId": "order-uuid",
  "userId": 1,
  "amount": 50000,
  "status": "DONE",
  "paymentKey": "test_xxx-xxx-xxx",
  "transactionKey": "tx_test_xxx-xxx-xxx",
  "method": "CARD",
  "orderName": "상품명 외 2건",
  "createdAt": "2025-01-15T10:30:00",
  "approvedAt": "2025-01-15T10:30:05"
}
```

---

#### 2. Application Service Layer (PaymentApplicationService.java:143-172)

##### 2-1. Payment 조회 및 검증
```java
Payment payment = paymentRepository.findByOrderId(orderId)
    .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다"));
```

**데이터 흐름:**
```
orderId (UUID)
  → PaymentRepository
  → Payment Entity
```

**Payment 엔티티 예시:**
```
Payment {
  id: UUID
  orderId: UUID
  userId: 1L
  amount: 50000
  status: PENDING
  method: CARD
  orderName: "상품명 외 2건"
  paymentKey: null
  transactionKey: null
}
```

---

##### 2-2. Payment 승인 처리
```java
String testPaymentKey = "test_" + UUID.randomUUID().toString();
String testTransactionId = "tx_test_" + UUID.randomUUID().toString();
payment.approve(testPaymentKey, testTransactionId);
paymentRepository.save(payment);
```

**상태 변경:**
```
BEFORE:
Payment {
  status: PENDING
  paymentKey: null
  transactionKey: null
  approvedAt: null
}

AFTER:
Payment {
  status: DONE
  paymentKey: "test_3fa85f64-5717-4562-b3fc-2c963f66afa6"
  transactionKey: "tx_test_3fa85f64-5717-4562-b3fc-2c963f66afa6"
  approvedAt: "2025-01-15T10:30:05"
}
```

---

##### 2-3. Order 상태 변경
```java
Order order = payment.getOrder();
order.changeStatus(OrderStatus.CONFIRMED);
orderRepository.save(order);
```

**상태 변경:**
```
BEFORE:
Order {
  id: UUID
  userId: 1L
  status: PENDING
  subTotal: 60000
  discountAmount: 10000
  totalAmount: 50000
}

AFTER:
Order {
  status: CONFIRMED  // PENDING → CONFIRMED
  (나머지 필드는 동일)
}
```

---

##### 2-4. TICKET 상품 처리 (Raffle)
```java
processTicketProducts(order);
```

**동작:**
- Order의 OrderItem 중 ProductCategory가 TICKET인 상품 탐색
- Raffle 조회 및 검증
- RaffleTicket 생성 (응모 티켓 발행)

**데이터 흐름 예시:**
```
OrderItem {
  productId: "ticket-product-uuid"
  quantity: 2
  category: TICKET
}
  → Raffle 조회
  → RaffleTicket 생성 (수량만큼)

결과:
RaffleTicket [
  { raffleId: 1, userId: 1, ticketNumber: 100 },
  { raffleId: 1, userId: 1, ticketNumber: 101 }
]
```

---

##### 2-5. 재고 차감 (핵심 로직)
```java
reduceProductStock(order);
```

**재고 차감 상세 로직 (PaymentApplicationService.java:251-263):**
```java
private void reduceProductStock(Order order) {
    for (OrderItem orderItem : order.getOrderItems()) {
        // 1. Product 조회
        Product product = productRepository.findById(orderItem.getProductId())
            .orElseThrow(() -> new IllegalArgumentException(
                "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

        // 2. 재고 차감
        product.decreaseStock(orderItem.getQuantity());

        // 3. Product 저장
        productRepository.save(product);

        log.info("[STOCK_REDUCE] Product stock reduced - ProductId: {}, Quantity: {}, Remaining: {}",
            product.getId(), orderItem.getQuantity(), product.getStock());
    }
}
```

**재고 차감 데이터 흐름 예시:**

**주문 상품 (OrderItem):**
```
OrderItem 1:
  productId: "product-uuid-1"
  productName: "맥북 프로"
  quantity: 1
  price: 3000000

OrderItem 2:
  productId: "product-uuid-2"
  productName: "아이폰 15"
  quantity: 2
  price: 1500000
```

**재고 변경:**
```
BEFORE:
Product 1 (맥북 프로):
  id: "product-uuid-1"
  stock: 50
  status: AVAILABLE

Product 2 (아이폰 15):
  id: "product-uuid-2"
  stock: 100
  status: AVAILABLE

AFTER:
Product 1 (맥북 프로):
  stock: 49  (50 - 1)
  status: AVAILABLE

Product 2 (아이폰 15):
  stock: 98  (100 - 2)
  status: AVAILABLE
```

**재고가 0이 되는 경우:**
```
BEFORE:
Product {
  stock: 1
  status: AVAILABLE
}

주문 수량: 1

AFTER:
Product {
  stock: 0
  status: OUT_OF_STOCK  // 자동으로 품절 상태로 변경
}
```

**로그 출력 예시:**
```
[STOCK_REDUCE] Product stock reduced - ProductId: product-uuid-1, Quantity: 1, Remaining: 49
[STOCK_REDUCE] Product stock reduced - ProductId: product-uuid-2, Quantity: 2, Remaining: 98
```

---

##### 2-6. Domain Layer - 재고 차감 세부 로직

**Product.decreaseStock() (Product.java:59-62):**
```java
public void decreaseStock(int quantity) {
    this.stock = this.stock.decrease(quantity);
    updateStatusByStock();
}
```

**Stock Value Object (Stock.java:38-46):**
```java
public Stock decrease(Integer amount) {
    if (amount <= 0) {
        throw new IllegalArgumentException("감소할 수량은 양수여야 합니다.");
    }
    if (this.amount < amount) {
        throw new IllegalArgumentException("재고가 부족합니다.");
    }
    return new Stock(this.amount - amount);
}
```

**검증 로직:**
1. 차감 수량이 양수인지 확인
2. 현재 재고가 차감 수량보다 크거나 같은지 확인
3. 검증 실패 시 예외 발생 (트랜잭션 롤백)

---

## 실제 결제 승인 흐름

### API 엔드포인트
```
POST /payments/confirm
```

### 요청 데이터 예시
```json
{
  "paymentKey": "tviva20240115103000ABCD",
  "orderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": 50000
}
```

### 처리 흐름

테스트용 흐름과 유사하지만, 다음 차이점이 있습니다:

#### 1. Toss Payments API 호출 (PaymentApplicationService.java:96-106)
```java
TossPaymentConfirmRequest request = new TossPaymentConfirmRequest(
    paymentKey,
    orderId.toString(),
    amount
);
TossPaymentResponse response = tossPaymentClient.confirmPayment(request);
```

**Toss API 요청:**
```json
POST https://api.tosspayments.com/v1/payments/confirm
{
  "paymentKey": "tviva20240115103000ABCD",
  "orderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": 50000
}
```

**Toss API 응답:**
```json
{
  "paymentKey": "tviva20240115103000ABCD",
  "transactionKey": "9C62F8E9E3C5D4B2A1F7",
  "status": "DONE",
  "orderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "orderName": "상품명 외 2건",
  "method": "카드",
  "totalAmount": 50000,
  "approvedAt": "2025-01-15T10:30:05"
}
```

#### 2. 실제 paymentKey와 transactionKey 사용
```java
payment.approve(response.getPaymentKey(), response.getTransactionKey());
```

#### 3. 예외 처리
```java
try {
    // Toss API 호출 및 결제 승인
} catch (Exception e) {
    // 결제 실패 처리
    payment.fail("PAYMENT_FAILED", e.getMessage());
    paymentRepository.save(payment);
    throw new RuntimeException("결제 승인에 실패했습니다: " + e.getMessage(), e);
}
```

**실패 시 Payment 상태:**
```
Payment {
  status: FAILED
  failureCode: "PAYMENT_FAILED"
  failureMessage: "카드 한도 초과"
}
```

#### 4. 이후 흐름은 테스트 모드와 동일
- Order 상태 변경 (PENDING → CONFIRMED)
- 재고 차감
- TICKET 상품 처리

---

## 데이터 흐름 예시

### 전체 흐름 시퀀스 다이어그램

```
Client                Controller              Application Service          Repository              Domain
  |                       |                           |                         |                      |
  |-- POST /confirm/test->|                           |                         |                      |
  |                       |                           |                         |                      |
  |                       |--confirmPaymentForTest -->|                         |                      |
  |                       |                           |                         |                      |
  |                       |                           |--findByOrderId--------->|                      |
  |                       |                           |<--Payment---------------|                      |
  |                       |                           |                         |                      |
  |                       |                           |--payment.approve()------|--------------------->|
  |                       |                           |                         |                      |
  |                       |                           |--save(payment)--------->|                      |
  |                       |                           |                         |                      |
  |                       |                           |--order.changeStatus()---|--------------------->|
  |                       |                           |                         |                      |
  |                       |                           |--save(order)----------->|                      |
  |                       |                           |                         |                      |
  |                       |                           |--processTicketProducts->|                      |
  |                       |                           |   (TICKET 상품 처리)     |                      |
  |                       |                           |                         |                      |
  |                       |                           |--reduceProductStock---->|                      |
  |                       |                           |  |                      |                      |
  |                       |                           |  |--findById(productId)>|                      |
  |                       |                           |  |<--Product------------|                      |
  |                       |                           |  |                      |                      |
  |                       |                           |  |--product.decreaseStock()------------------>|
  |                       |                           |  |                      |                      |
  |                       |                           |  |--save(product)------>|                      |
  |                       |                           |  |                      |                      |
  |                       |                           |<--(재고 차감 완료)------|                      |
  |                       |                           |                         |                      |
  |                       |<--Payment-----------------|                         |                      |
  |                       |                           |                         |                      |
  |<--PaymentResponse-----|                           |                         |                      |
```

---

### 완전한 예시: 맥북 1개, 아이폰 2개 주문

#### Step 1: 초기 상태

**Order:**
```json
{
  "id": "order-123-uuid",
  "userId": 1,
  "status": "PENDING",
  "subTotal": 6000000,
  "discountAmount": 600000,
  "totalAmount": 5400000,
  "orderItems": [
    {
      "productId": "macbook-uuid",
      "productName": "맥북 프로",
      "price": 3000000,
      "quantity": 1
    },
    {
      "productId": "iphone-uuid",
      "productName": "아이폰 15",
      "price": 1500000,
      "quantity": 2
    }
  ]
}
```

**Payment:**
```json
{
  "id": "payment-456-uuid",
  "orderId": "order-123-uuid",
  "userId": 1,
  "amount": 5400000,
  "status": "PENDING",
  "method": "CARD",
  "orderName": "맥북 프로 외 1건"
}
```

**Products (재고):**
```json
[
  {
    "id": "macbook-uuid",
    "name": "맥북 프로",
    "stock": 50,
    "status": "AVAILABLE"
  },
  {
    "id": "iphone-uuid",
    "name": "아이폰 15",
    "stock": 100,
    "status": "AVAILABLE"
  }
]
```

---

#### Step 2: 결제 승인 API 호출

**Request:**
```http
POST /payments/confirm/test
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

{
  "orderId": "order-123-uuid"
}
```

---

#### Step 3: 결제 승인 처리

**Payment 상태 변경:**
```json
{
  "id": "payment-456-uuid",
  "status": "DONE",  // PENDING → DONE
  "paymentKey": "test_7a8b9c-1d2e-3f4g-5h6i-7j8k9l0m1n2o",
  "transactionKey": "tx_test_3p4q5r-6s7t-8u9v-0w1x-2y3z4a5b6c7d",
  "approvedAt": "2025-01-15T10:30:05"
}
```

---

#### Step 4: Order 상태 변경

**Order 상태 변경:**
```json
{
  "id": "order-123-uuid",
  "status": "CONFIRMED",  // PENDING → CONFIRMED
  // 나머지 필드는 동일
}
```

---

#### Step 5: 재고 차감

**맥북 프로 재고 차감:**
```
BEFORE: stock = 50
주문 수량: 1
AFTER: stock = 49
```

**아이폰 15 재고 차감:**
```
BEFORE: stock = 100
주문 수량: 2
AFTER: stock = 98
```

**최종 Products 상태:**
```json
[
  {
    "id": "macbook-uuid",
    "name": "맥북 프로",
    "stock": 49,  // 50 → 49
    "status": "AVAILABLE"
  },
  {
    "id": "iphone-uuid",
    "name": "아이폰 15",
    "stock": 98,  // 100 → 98
    "status": "AVAILABLE"
  }
]
```

---

#### Step 6: 최종 응답

**Response:**
```json
{
  "id": "payment-456-uuid",
  "orderId": "order-123-uuid",
  "userId": 1,
  "amount": 5400000,
  "status": "DONE",
  "paymentKey": "test_7a8b9c-1d2e-3f4g-5h6i-7j8k9l0m1n2o",
  "transactionKey": "tx_test_3p4q5r-6s7t-8u9v-0w1x-2y3z4a5b6c7d",
  "method": "CARD",
  "orderName": "맥북 프로 외 1건",
  "createdAt": "2025-01-15T10:25:00",
  "approvedAt": "2025-01-15T10:30:05"
}
```

---

## 주요 컴포넌트

### 1. PaymentController
- **역할**: REST API 엔드포인트 제공
- **위치**: `interfaces/payment/PaymentController.java`
- **주요 메서드**:
  - `POST /payments/confirm/test` - 테스트용 결제 승인
  - `POST /payments/confirm` - 실제 결제 승인
  - `POST /payments/cancel` - 결제 취소

### 2. PaymentApplicationService
- **역할**: 결제 비즈니스 로직 조율
- **위치**: `application/payment/PaymentApplicationService.java`
- **주요 메서드**:
  - `confirmPaymentForTest()` - 테스트 결제 승인
  - `confirmPayment()` - 실제 결제 승인
  - `reduceProductStock()` - 재고 차감
  - `processTicketProducts()` - Raffle 티켓 처리

### 3. Payment (Domain)
- **역할**: 결제 도메인 모델
- **주요 메서드**:
  - `approve(paymentKey, transactionKey)` - 결제 승인
  - `fail(code, message)` - 결제 실패
  - `cancel()` - 결제 취소

### 4. Product (Domain)
- **역할**: 상품 도메인 모델
- **위치**: `domain/product/model/Product.java`
- **주요 메서드**:
  - `decreaseStock(quantity)` - 재고 차감
  - `increaseStock(quantity)` - 재고 증가

### 5. Stock (Value Object)
- **역할**: 재고 값 객체 (불변 객체)
- **위치**: `domain/product/model/vo/Stock.java`
- **주요 메서드**:
  - `decrease(amount)` - 재고 감소 (새 Stock 객체 반환)
  - `increase(amount)` - 재고 증가 (새 Stock 객체 반환)

---

## 트랜잭션 및 동시성 제어

### 트랜잭션 범위
```java
@Transactional
public Payment confirmPaymentForTest(UUID orderId) {
    // 이 메서드 전체가 하나의 트랜잭션
    // 중간에 예외 발생 시 모든 변경사항 롤백
}
```

### 동시성 제어 고려사항

**문제 시나리오:**
- 사용자 A와 사용자 B가 동시에 마지막 1개 재고를 주문하는 경우

**현재 구조:**
- Product 조회 및 저장은 일반 조회 사용
- 높은 동시성 환경에서는 재고 부족 현상 발생 가능

**개선 방안 (향후 고려):**
```java
// Pessimistic Lock 사용
@Lock(LockModeType.PESSIMISTIC_WRITE)
Product findByIdForUpdate(UUID id);
```

---

## 결제 취소 및 재고 복구

결제 취소 시 `restoreProductStock()` 메서드가 호출되어 재고가 자동으로 복구됩니다.

**재고 복구 로직 (PaymentApplicationService.java:265-277):**
```java
private void restoreProductStock(Order order) {
    for (OrderItem orderItem : order.getOrderItems()) {
        Product product = productRepository.findById(orderItem.getProductId())
            .orElseThrow(() -> new IllegalArgumentException(
                "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

        product.increaseStock(orderItem.getQuantity());
        productRepository.save(product);

        log.info("[STOCK_RESTORE] Product stock restored - ProductId: {}, Quantity: {}, Current: {}",
            product.getId(), orderItem.getQuantity(), product.getStock());
    }
}
```

---

## 참고 자료

- [Toss Payments 공식 문서](https://docs.tosspayments.com/)
- `backend/TOSS_PAYMENTS_GUIDE.md` - Toss Payments 연동 가이드
- `backend/CLAUDE.md` - 프로젝트 아키텍처 개요
