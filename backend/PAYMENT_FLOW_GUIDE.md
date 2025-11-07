# 결제 흐름 가이드 (Payment Flow Guide)

## 목차
1. [개요](#개요)
2. [테스트용 결제 승인 흐름](#테스트용-결제-승인-흐름)
3. [실제 결제 승인 흐름](#실제-결제-승인-흐름)
4. [주요 컴포넌트](#주요-컴포넌트)

---

## 개요

이 문서는 Groom Shopping 백엔드의 결제 시스템 흐름을 설명합니다. 결제 시스템은 크게 두 가지 모드로 동작합니다:

- **테스트 모드** (현재 문서): Toss Payments API 호출 없이 결제 승인 (개발/테스트용)
- **실제 모드** (향후 구현): Toss Payments API를 호출하여 실제 결제 승인
  - 프론트엔드 구현 완료 후 Toss API와 연동하여 테스트 예정

**현재 문서는 테스트용 결제 API를 중심으로 작성되었습니다.**

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

### 응답 데이터 예시
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

### 처리 흐름

#### 1. Controller Layer (PaymentController.java:83-99)
```java
@PostMapping("/confirm/test")
public ResponseEntity<PaymentResponse> confirmPaymentForTest(
    @AuthenticationPrincipal(expression = "user") User user,
    @RequestBody ConfirmPaymentRequest request)
```

**입력:**
- `user`: JWT 토큰에서 추출한 인증 사용자
- `request.orderId`: 결제할 주문 ID (UUID)

**출력:**
- `PaymentResponse`: 결제 완료 정보

---

#### 2. Application Service Layer (PaymentApplicationService.java:147-181)

##### 2-1. Payment 조회
```java
Payment payment = paymentRepository.findByOrderId(orderId)
    .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다: " + orderId));
```

**초기 Payment 상태:**
```json
{
  "id": "payment-uuid",
  "orderId": "order-uuid",
  "userId": 1,
  "amount": 50000,
  "status": "PENDING",
  "method": "CARD",
  "orderName": "맥북 프로 외 1건",
  "paymentKey": null,
  "transactionKey": null,
  "approvedAt": null
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

**승인 후 Payment 상태:**
```json
{
  "status": "DONE",
  "paymentKey": "test_7a8b9c-1d2e-3f4g-5h6i-7j8k9l0m1n2o",
  "transactionKey": "tx_test_3p4q5r-6s7t-8u9v-0w1x-2y3z4a5b6c7d",
  "approvedAt": "2025-01-15T10:30:05"
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
Order.status: PENDING → CONFIRMED
```

---

##### 2-4. 재고 차감 (핵심 로직)
```java
List<UUID> reducedProductIds = reduceProductStock(order);
```

**재고 차감 로직 (PaymentApplicationService.java:252-271):**
```java
private List<UUID> reduceProductStock(Order order) {
    List<UUID> reducedProductIds = new ArrayList<>();

    for (OrderItem orderItem : order.getOrderItems()) {
        // 1. Product 조회
        Product product = productRepository.findById(orderItem.getProductId())
            .orElseThrow(() -> new IllegalArgumentException(
                "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

        // 2. 재고 차감
        product.decreaseStock(orderItem.getQuantity());
        productRepository.save(product);

        // 3. 차감된 상품 ID 수집 (알림용)
        reducedProductIds.add(product.getId());

        log.info("[STOCK_REDUCE] Product stock reduced - ProductId: {}, Quantity: {}, Remaining: {}",
            product.getId(), orderItem.getQuantity(), product.getStock());
    }

    return reducedProductIds;
}
```

**재고 변경 예시:**
```
BEFORE:
  맥북 프로: stock = 50
  아이폰 15: stock = 100

주문 수량:
  맥북 프로: 1개
  아이폰 15: 2개

AFTER:
  맥북 프로: stock = 49  (50 - 1)
  아이폰 15: stock = 98  (100 - 2)
```

**재고 차감 검증 (Product.decreaseStock → Stock.decrease):**
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

---

##### 2-5. TICKET 상품 처리 (Raffle 티켓 발행)
```java
processTicketProducts(order);
```

**처리 로직 (PaymentApplicationService.java:289-324):**
```java
private void processTicketProducts(Order order) {
    Long userId = order.getUserId();

    for (OrderItem orderItem : order.getOrderItems()) {
        Product product = productRepository.findById(orderItem.getProductId())
            .orElseThrow(() -> new IllegalArgumentException(
                "상품을 찾을 수 없습니다: " + orderItem.getProductId()));

        // TICKET 카테고리가 아니면 건너뛰기
        if (product.getCategory() != ProductCategory.TICKET) {
            continue;
        }

        // Raffle 조회
        Raffle raffle = raffleRepository.findByRaffleProductId(product.getId())
            .orElseThrow(() -> new IllegalStateException(
                "해당 상품에 대한 추첨 정보를 찾을 수 없습니다: " + product.getId()));

        // 추첨 상태 검증
        raffleValidationService.validateRaffleForEntry(raffle);

        int quantity = orderItem.getQuantity();

        // 사용자 응모 한도 검증
        raffleValidationService.validateUserEntryLimit(raffle, userId, quantity);

        // 티켓 생성 (Redis 원자적 번호 할당 + DB 저장)
        raffleTicketApplicationService.createTickets(raffle, userId, quantity);
    }
}
```

**TICKET 상품 처리 흐름:**
```
1. OrderItem 중 TICKET 카테고리 상품 필터링
2. 해당 상품의 Raffle 조회
3. 추첨 상태 검증 (응모 가능 여부)
4. 사용자 응모 한도 검증
5. RaffleTicket 생성 (수량만큼)
   - Redis로 티켓 번호 범위 원자적 할당
   - 할당된 번호로 DB에 티켓 생성
```

**예시:**
```
OrderItem {
  productId: "raffle-product-uuid"
  productName: "응모권 3장"
  quantity: 3
  category: TICKET
}

→ Raffle 조회 및 검증
→ RaffleTicket 3개 생성:
  - { userId: 1, raffleId: 5, ticketNumber: 100 }
  - { userId: 1, raffleId: 5, ticketNumber: 101 }
  - { userId: 1, raffleId: 5, ticketNumber: 102 }
```

---

##### 2-6. 비동기 알림 처리 (응답 후 백그라운드 실행)
```java
paymentNotificationService.sendStockReducedNotifications(reducedProductIds);
```

**비동기 알림 서비스 (PaymentNotificationService.java:25-50):**
```java
@Async("notificationExecutor")
public void sendStockReducedNotifications(List<UUID> productIds) {
    log.info("[NOTIFICATION_ASYNC_START] Sending stock reduced notifications - ProductIds: {}", productIds);

    try {
        // 재고 임계값 알림 전송 (팀원 구현 메서드 호출)
        notificationApplicationService.createAndSendNotificationsForProducts(productIds);

        log.info("[NOTIFICATION_ASYNC_SUCCESS] Stock reduced notifications sent successfully - ProductIds: {}",
                productIds);

    } catch (Exception e) {
        // 알림 실패해도 결제는 성공 상태 유지
        log.error("[NOTIFICATION_ASYNC_FAILED] Failed to send stock reduced notifications - ProductIds: {}, Error: {}",
                productIds, e.getMessage(), e);
    }
}
```

**특징:**
- `@Async("notificationExecutor")`: 별도 스레드풀에서 실행
- 알림 실패해도 결제 응답에 영향 없음
- 재고가 임계값 이하로 떨어진 상품의 관리자에게 알림 전송

---

##### 2-7. 비동기 장바구니 비우기 (응답 후 백그라운드 실행)
```java
paymentNotificationService.clearCartItems(order);
```

**비동기 장바구니 비우기 서비스 (PaymentNotificationService.java:59-89):**
```java
@Async("notificationExecutor")
public void clearCartItems(Order order) {
    Long userId = order.getUserId();
    List<OrderItem> orderItems = order.getOrderItems();

    log.info("[CART_CLEAR_ASYNC_START] Clearing cart items - UserId: {}, OrderId: {}, ItemCount: {}",
            userId, order.getId(), orderItems.size());

    try {
        // OrderItem을 CartItemToRemove로 변환
        List<CartItemToRemove> itemsToRemove = orderItems.stream()
                .map(orderItem -> new CartItemToRemove(
                        orderItem.getProductId(),
                        orderItem.getQuantity()
                ))
                .collect(Collectors.toList());

        // 장바구니에서 제거
        cartApplicationService.removeCartItems(userId, itemsToRemove);

        log.info("[CART_CLEAR_ASYNC_SUCCESS] Cart items cleared successfully - UserId: {}, OrderId: {}, ItemCount: {}",
                userId, order.getId(), itemsToRemove.size());

    } catch (Exception e) {
        // 장바구니 비우기 실패해도 결제는 성공 상태 유지
        log.error("[CART_CLEAR_ASYNC_FAILED] Failed to clear cart items - UserId: {}, OrderId: {}, Error: {}",
                userId, order.getId(), e.getMessage(), e);
    }
}
```

**특징:**
- `@Async("notificationExecutor")`: 별도 스레드풀에서 실행
- 장바구니 비우기 실패해도 결제 응답에 영향 없음
- Order의 OrderItem만 장바구니에서 제거 (수량 정확히 일치)
- `CartApplicationService.removeCartItems()` 활용

**장바구니 비우기 예시:**
```
Order.orderItems:
  - 맥북 프로 1개
  - 아이폰 15 2개

User의 장바구니 (결제 전):
  - 맥북 프로 1개
  - 아이폰 15 2개
  - 에어팟 1개

장바구니 비우기 실행:
  - 맥북 프로 1개 제거 → 완전 제거
  - 아이폰 15 2개 제거 → 완전 제거
  - 에어팟 1개는 유지 (주문하지 않은 상품)

User의 장바구니 (결제 후):
  - 에어팟 1개만 남음
```

---

##### 2-8. Payment 반환 및 응답
```java
return payment;
```

**최종 응답 (PaymentController → PaymentResponse):**
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

### 전체 처리 흐름 요약

```
[Client Request]
    ↓
[1. Payment 조회 및 검증]
    ↓
[2. Payment 승인 처리] ← 트랜잭션 시작
    ↓
[3. Order 상태 변경 (PENDING → CONFIRMED)]
    ↓
[4. 재고 차감 + ProductId 수집]
    ↓
[5. TICKET 상품 처리 (Raffle 티켓 발행)]
    ↓
[트랜잭션 커밋]
    ↓
[Client Response] ← 즉시 응답 반환
    ↓
[비동기 처리 시작] ← 별도 스레드
    ├─ [6. 재고 임계값 알림 전송]
    └─ [7. 장바구니 비우기]
```

**트랜잭션 범위:**
- Payment 승인 ~ TICKET 발행: 단일 트랜잭션
- 알림 전송, 장바구니 비우기: 별도 트랜잭션 (실패해도 결제는 성공)

---

### 비동기 처리 설정 (AsyncConfig.java)

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);          // 기본 스레드 수
        executor.setMaxPoolSize(10);          // 최대 스레드 수
        executor.setQueueCapacity(100);       // 큐 크기
        executor.setThreadNamePrefix("notification-async-");
        executor.initialize();
        return executor;
    }
}
```

**스레드풀 동작:**
- 기본 5개 스레드로 비동기 작업 처리
- 큐가 가득 차면 최대 10개까지 스레드 증가
- 알림 전송과 장바구니 비우기를 동시에 처리 가능

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

---

### 처리 흐름 (향후 프론트엔드 완성 후 테스트 예정)

테스트용 결제와 유사하지만, 다음 차이점이 있습니다:

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
```http
POST https://api.tosspayments.com/v1/payments/confirm
Authorization: Basic {Secret Key를 Base64 인코딩}
Content-Type: application/json

{
  "paymentKey": "tviva20240115103000ABCD",
  "orderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amount": 50000
}
```

**Toss API 응답 (성공):**
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

---

#### 2. 실제 paymentKey와 transactionKey 사용
```java
payment.approve(response.getPaymentKey(), response.getTransactionKey());
```

---

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
```json
{
  "status": "FAILED",
  "failureCode": "PAYMENT_FAILED",
  "failureMessage": "카드 한도 초과"
}
```

---

#### 4. 이후 흐름은 테스트 모드와 동일
- Order 상태 변경 (PENDING → CONFIRMED)
- 재고 차감
- TICKET 상품 처리
- 비동기 알림 전송
- 비동기 장바구니 비우기

---

### 향후 테스트 계획

**프론트엔드 구현 완료 후:**
1. Toss Payments 위젯 연동
2. 실제 결제 플로우 테스트
3. 성공/실패 케이스별 검증
4. 결제 취소 플로우 테스트
5. 재고 복구 로직 검증

**참고 문서:**
- `backend/TOSS_PAYMENTS_GUIDE.md` - Toss Payments 연동 가이드

---

## 주요 컴포넌트

### 1. PaymentController
- **역할**: REST API 엔드포인트 제공
- **위치**: `interfaces/payment/PaymentController.java`
- **주요 메서드**:
  - `POST /payments/confirm/test` - 테스트용 결제 승인
  - `POST /payments/confirm` - 실제 결제 승인 (향후 테스트)
  - `POST /payments/cancel` - 결제 취소

---

### 2. PaymentApplicationService
- **역할**: 결제 비즈니스 로직 조율
- **위치**: `application/payment/PaymentApplicationService.java`
- **주요 메서드**:
  - `confirmPaymentForTest(orderId)` - 테스트 결제 승인
  - `confirmPayment(paymentKey, orderId, amount)` - 실제 결제 승인
  - `reduceProductStock(order)` - 재고 차감
  - `processTicketProducts(order)` - Raffle 티켓 처리

---

### 3. PaymentNotificationService
- **역할**: 결제 완료 후 비동기 처리
- **위치**: `application/payment/PaymentNotificationService.java`
- **주요 메서드**:
  - `sendStockReducedNotifications(productIds)` - 비동기 알림 전송
  - `clearCartItems(order)` - 비동기 장바구니 비우기
- **특징**:
  - `@Async` 사용하여 별도 스레드에서 실행
  - 실패해도 결제 응답에 영향 없음

---

### 4. Payment (Domain)
- **역할**: 결제 도메인 모델
- **위치**: `domain/payment/model/Payment.java`
- **주요 메서드**:
  - `approve(paymentKey, transactionKey)` - 결제 승인
  - `fail(code, message)` - 결제 실패
  - `cancel()` - 결제 취소

---

### 5. Product (Domain)
- **역할**: 상품 도메인 모델
- **위치**: `domain/product/model/Product.java`
- **주요 메서드**:
  - `decreaseStock(quantity)` - 재고 차감
  - `increaseStock(quantity)` - 재고 증가 (결제 취소 시)

---

### 6. Stock (Value Object)
- **역할**: 재고 값 객체 (불변 객체)
- **위치**: `domain/product/model/vo/Stock.java`
- **주요 메서드**:
  - `decrease(amount)` - 재고 감소 (새 Stock 객체 반환)
  - `increase(amount)` - 재고 증가 (새 Stock 객체 반환)
- **검증**:
  - 차감 수량이 양수인지 확인
  - 현재 재고가 충분한지 확인

---

## 트랜잭션 및 동시성 제어

### 트랜잭션 범위

```java
@Transactional
public Payment confirmPaymentForTest(UUID orderId) {
    // 1. Payment 승인
    // 2. Order 상태 변경
    // 3. 재고 차감
    // 4. TICKET 발행
    // ← 여기까지 단일 트랜잭션 (예외 발생 시 모두 롤백)
}

// 비동기 처리는 별도 트랜잭션
@Async
public void sendStockReducedNotifications(...) {
    // 독립적인 트랜잭션 (실패해도 결제는 성공)
}

@Async
public void clearCartItems(...) {
    // 독립적인 트랜잭션 (실패해도 결제는 성공)
}
```

---

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

**재고 복구 로직 (PaymentApplicationService.java:273-285):**
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

**재고 복구 예시:**
```
BEFORE (결제 완료 후):
  맥북 프로: stock = 49
  아이폰 15: stock = 98

결제 취소:
  맥북 프로: 1개 복구
  아이폰 15: 2개 복구

AFTER (결제 취소 후):
  맥북 프로: stock = 50
  아이폰 15: stock = 100
```

---

## 참고 자료

- [Toss Payments 공식 문서](https://docs.tosspayments.com/)
- `backend/TOSS_PAYMENTS_GUIDE.md` - Toss Payments 연동 가이드
- `backend/ORDER_CREATION_FLOW_GUIDE.md` - 주문 생성 흐름
- `backend/CLAUDE.md` - 프로젝트 아키텍처 개요
