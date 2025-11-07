# 주문 생성 흐름 가이드 (Order Creation Flow Guide)

## 목차
1. [개요](#개요)
2. [주문 생성 흐름](#주문-생성-흐름)
3. [데이터 흐름 예시](#데이터-흐름-예시)
4. [주요 컴포넌트](#주요-컴포넌트)
5. [재고 확인 vs 재고 차감](#재고-확인-vs-재고-차감)

---

## 개요

이 문서는 Groom Shopping 백엔드의 주문 생성 시스템 흐름을 설명합니다. 주문 생성은 다음과 같은 단계로 진행됩니다:

1. 사용자 장바구니 조회
2. 상품 정보 조회 및 검증
3. 주문 및 주문 아이템 생성
4. 쿠폰 할인 적용
5. Payment 자동 생성

**중요:** 주문 생성 시에는 재고 **확인만** 하고, 실제 재고 차감은 **결제 승인 시점**에 발생합니다.

---

## 주문 생성 흐름

### API 엔드포인트
```
POST /orders
```
(실제 엔드포인트는 OrderController 구현에 따라 다를 수 있음)

### 요청 데이터 예시
```json
{
  "couponId": 123
}
```
- `couponId`: 선택사항. 사용할 쿠폰 ID (없으면 null)
- `userId`: JWT 토큰에서 자동 추출

---

### 처리 흐름

#### 1. Controller Layer
```java
@PostMapping("/orders")
public ResponseEntity<OrderResponse> createOrder(
    @AuthenticationPrincipal(expression = "user") User user,
    @RequestBody CreateOrderRequest request)
```

**입력 데이터:**
- `user`: 인증된 사용자 정보 (JWT 토큰에서 추출)
- `request.couponId`: 사용할 쿠폰 ID (선택사항)

---

#### 2. Application Service Layer (OrderApplicationService.java:36-149)

##### 2-1. 사용자 장바구니 조회
```java
List<CartItemJpaEntity> cartItemProducts = cartItemRepository.findByUserId(userId);

if (cartItemProducts.isEmpty()) {
    throw new IllegalArgumentException("장바구니가 비어있습니다.");
}
```

**CartItem 데이터 예시:**
```json
[
  {
    "id": 1,
    "userId": 1,
    "productId": "macbook-uuid",
    "quantity": 1,
    "createdAt": "2025-01-15T09:00:00"
  },
  {
    "id": 2,
    "userId": 1,
    "productId": "iphone-uuid",
    "quantity": 2,
    "createdAt": "2025-01-15T09:05:00"
  }
]
```

**데이터 흐름:**
```
userId (Long)
  → SpringDataCartItemRepository.findByUserId(userId)
  → List<CartItemJpaEntity>
```

---

##### 2-2. ProductId 추출
```java
List<UUID> productIds = cartItemProducts.stream()
    .map(CartItemJpaEntity::getProductId)
    .toList();
```

**추출된 productIds:**
```json
[
  "macbook-uuid",
  "iphone-uuid"
]
```

---

##### 2-3. 상품 정보 조회
```java
List<ProductJpaEntity> products = productRepository.findByIdIn(productIds);

if (products.size() != productIds.size()) {
    throw new IllegalArgumentException("일부 상품 정보를 찾을 수 없습니다.");
}
```

**조회된 Product 데이터:**
```json
[
  {
    "id": "macbook-uuid",
    "name": "맥북 프로",
    "description": "M3 Pro 칩 탑재",
    "price": 3000000,
    "stock": 50,
    "category": "ELECTRONICS",
    "isActive": true,
    "status": "AVAILABLE"
  },
  {
    "id": "iphone-uuid",
    "name": "아이폰 15",
    "description": "최신 아이폰",
    "price": 1500000,
    "stock": 100,
    "category": "ELECTRONICS",
    "isActive": true,
    "status": "AVAILABLE"
  }
]
```

**데이터 흐름:**
```
productIds (List<UUID>)
  → SpringDataProductRepository.findByIdIn(productIds)
  → List<ProductJpaEntity>
  → Map<UUID, ProductJpaEntity> (검색 최적화)
```

---

##### 2-4. Order 기본 객체 생성
```java
Order order = Order.builder()
    .userId(userId)
    .couponId(couponId)
    .build();
```

**초기 Order 상태:**
```json
{
  "id": null,  // 아직 저장 전
  "userId": 1,
  "couponId": 123,
  "status": "PENDING",
  "subTotal": 0,
  "discountAmount": 0,
  "totalAmount": 0,
  "orderItems": [],
  "createdAt": null
}
```

---

##### 2-5. OrderItem 생성 및 검증 (핵심 로직)

```java
for (CartItemJpaEntity cartItem : cartItemProducts) {
    UUID productId = cartItem.getProductId();
    Integer quantity = cartItem.getQuantity();

    // 상품 조회
    ProductJpaEntity product = productMap.get(productId);

    if (product == null) {
        throw new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId);
    }

    // 상품 상태 확인
    if (!product.getIsActive()) {
        throw new IllegalArgumentException(
            String.format("상품을 구매할 수 없습니다: %s", product.getName())
        );
    }

    // 재고 확인 (차감은 하지 않음!)
    if (product.getStock() < quantity) {
        throw new IllegalArgumentException(
            String.format("재고가 부족합니다. 상품: %s, 요청: %d, 재고: %d",
                product.getName(),
                quantity,
                product.getStock())
        );
    }

    // OrderItem 생성 (주문 시점의 상품 정보 스냅샷)
    OrderItem orderItem = OrderItem.builder()
        .productId(product.getId())
        .productName(product.getName())
        .price(product.getPrice())
        .quantity(quantity)
        .build();

    // Order에 OrderItem 추가
    order.addOrderItem(orderItem);

    log.info("OrderItem added - Product: {}, Quantity: {}, Price: {}, Subtotal: {}",
        orderItem.getProductName(),
        orderItem.getQuantity(),
        orderItem.getPrice(),
        orderItem.getSubtotal());
}
```

**검증 항목:**
1. **상품 존재 여부**: productMap에서 조회 가능한지 확인
2. **상품 활성 상태**: `product.getIsActive() == true`
3. **재고 충분 여부**: `product.getStock() >= quantity`

**중요: 재고 확인 vs 재고 차감**
```
주문 생성 시점:
  - 재고 확인: ✅ (stock >= quantity 검증)
  - 재고 차감: ❌ (차감하지 않음)

결제 승인 시점:
  - 재고 확인: ✅ (다시 한번 검증)
  - 재고 차감: ✅ (실제로 차감)
```

**OrderItem 데이터 (상품 정보 스냅샷):**
```json
{
  "id": null,
  "productId": "macbook-uuid",
  "productName": "맥북 프로",  // 주문 시점 이름
  "price": 3000000,            // 주문 시점 가격
  "quantity": 1,
  "subtotal": 3000000          // price * quantity
}
```

**왜 스냅샷을 저장하는가?**
- 주문 후 상품 가격이나 이름이 변경되어도 주문 내역은 변하지 않음
- 나중에 주문 내역 조회 시 주문 당시의 정보를 정확히 보여줄 수 있음

---

##### 2-6. Order 금액 계산 (1차)
```java
order.calculateAmounts();
```

**Order.calculateAmounts() 로직:**
```java
public void calculateAmounts() {
    // 1. 주문 아이템들의 소계 합산
    this.subTotal = orderItems.stream()
        .mapToInt(OrderItem::getSubtotal)
        .sum();

    // 2. 최종 금액 = 소계 - 할인 금액
    this.totalAmount = this.subTotal - this.discountAmount;
}
```

**1차 계산 결과:**
```json
{
  "subTotal": 6000000,     // 3000000 + (1500000 * 2)
  "discountAmount": 0,     // 쿠폰 적용 전
  "totalAmount": 6000000   // subTotal - discountAmount
}
```

---

##### 2-7. 쿠폰 할인 적용 (선택사항)
```java
if (couponId != null) {
    Integer discountAmount = couponIssueService.calculateDiscount(
        couponId, userId, order.getSubTotal()
    );
    order.setDiscountAmount(discountAmount);
    log.info("Coupon applied - couponId: {}, discountAmount: {}", couponId, discountAmount);
}
```

**쿠폰 할인 계산 예시:**
```
쿠폰 타입: PERCENTAGE (10% 할인)
주문 금액: 6,000,000원
할인 금액: 600,000원 (6,000,000 * 0.1)
```

**할인 적용 후:**
```json
{
  "discountAmount": 600000
}
```

---

##### 2-8. Order 금액 재계산 (2차)
```java
order.calculateAmounts();
```

**2차 계산 결과:**
```json
{
  "subTotal": 6000000,
  "discountAmount": 600000,
  "totalAmount": 5400000  // 6000000 - 600000
}
```

---

##### 2-9. Order 저장
```java
Order savedOrder = orderRepository.save(order);
```

**저장된 Order:**
```json
{
  "id": "order-123-uuid",
  "userId": 1,
  "couponId": 123,
  "status": "PENDING",
  "subTotal": 6000000,
  "discountAmount": 600000,
  "totalAmount": 5400000,
  "orderItems": [
    {
      "id": 1,
      "orderId": "order-123-uuid",
      "productId": "macbook-uuid",
      "productName": "맥북 프로",
      "price": 3000000,
      "quantity": 1,
      "subtotal": 3000000
    },
    {
      "id": 2,
      "orderId": "order-123-uuid",
      "productId": "iphone-uuid",
      "productName": "아이폰 15",
      "price": 1500000,
      "quantity": 2,
      "subtotal": 3000000
    }
  ],
  "createdAt": "2025-01-15T10:00:00"
}
```

**데이터베이스 저장:**
```
Order (orders 테이블):
  - id, userId, couponId, status, subTotal, discountAmount, totalAmount, createdAt

OrderItem (order_items 테이블, cascade 저장):
  - id, orderId, productId, productName, price, quantity, subtotal
```

---

##### 2-10. Payment 자동 생성
```java
String orderName = createOrderName(savedOrder.getOrderItems());

Payment payment = Payment.builder()
    .order(savedOrder)
    .userId(userId)
    .amount(savedOrder.getTotalAmount())
    .orderName(orderName)
    .method(PaymentMethod.CARD)  // 기본값: 카드 결제
    .build();

Payment savedPayment = paymentRepository.save(payment);
```

**주문명 생성 로직:**
```java
private String createOrderName(List<OrderItem> orderItems) {
    if (orderItems.isEmpty()) {
        return "주문";
    }

    OrderItem firstItem = orderItems.get(0);
    if (orderItems.size() == 1) {
        return firstItem.getProductName();  // "맥북 프로"
    }

    return firstItem.getProductName() + " 외 " + (orderItems.size() - 1) + "건";
    // "맥북 프로 외 1건"
}
```

**생성된 Payment:**
```json
{
  "id": "payment-456-uuid",
  "orderId": "order-123-uuid",
  "userId": 1,
  "amount": 5400000,
  "orderName": "맥북 프로 외 1건",
  "method": "CARD",
  "status": "PENDING",
  "paymentKey": null,
  "transactionKey": null,
  "approvedAt": null,
  "createdAt": "2025-01-15T10:00:00"
}
```

---

##### 2-11. Order에 Payment 연결
```java
savedOrder.assignPayment(savedPayment);
orderRepository.save(savedOrder);
```

**Order와 Payment 양방향 연결:**
```
Order {
  id: "order-123-uuid"
  paymentId: "payment-456-uuid"
}

Payment {
  id: "payment-456-uuid"
  orderId: "order-123-uuid"
}
```

**로그 출력:**
```
[PAYMENT_AUTO_CREATED] Payment automatically created -
  PaymentId: payment-456-uuid,
  OrderId: order-123-uuid,
  Amount: 5400000
```

---

##### 2-12. Order 반환
```java
return savedOrder;
```

---

## 데이터 흐름 예시

### 전체 흐름 시퀀스 다이어그램

```
Client          Controller      Application Service    Repository           Domain
  |                 |                   |                    |                   |
  |-- POST /orders->|                   |                    |                   |
  |                 |                   |                    |                   |
  |                 |--createOrder----->|                    |                   |
  |                 |                   |                    |                   |
  |                 |                   |--findByUserId----->|                   |
  |                 |                   |<--CartItems--------|                   |
  |                 |                   |                    |                   |
  |                 |                   |--findByIdIn------->|                   |
  |                 |                   |<--Products---------|                   |
  |                 |                   |                    |                   |
  |                 |                   |--Order.builder()------------------->  |
  |                 |                   |<--Order---------------------------- |
  |                 |                   |                    |                   |
  |                 |                   |-- 상품 상태 검증 ---|                   |
  |                 |                   |-- 재고 확인 --------|                   |
  |                 |                   |                    |                   |
  |                 |                   |--OrderItem 생성-------------------->  |
  |                 |                   |<--OrderItem------------------------- |
  |                 |                   |                    |                   |
  |                 |                   |--order.addOrderItem()--------------->  |
  |                 |                   |                    |                   |
  |                 |                   |--order.calculateAmounts()----------->  |
  |                 |                   |                    |                   |
  |                 |                   |--쿠폰 할인 계산---->|                   |
  |                 |                   |                    |                   |
  |                 |                   |--order.calculateAmounts()----------->  |
  |                 |                   |                    |                   |
  |                 |                   |--save(order)------>|                   |
  |                 |                   |<--Order(saved)-----|                   |
  |                 |                   |                    |                   |
  |                 |                   |--Payment.builder()----------------->  |
  |                 |                   |<--Payment--------------------------- |
  |                 |                   |                    |                   |
  |                 |                   |--save(payment)---->|                   |
  |                 |                   |<--Payment(saved)---|                   |
  |                 |                   |                    |                   |
  |                 |                   |--order.assignPayment()-------------->  |
  |                 |                   |                    |                   |
  |                 |                   |--save(order)------>|                   |
  |                 |                   |                    |                   |
  |                 |<--Order-----------|                    |                   |
  |                 |                   |                    |                   |
  |<--OrderResponse-|                   |                    |                   |
```

---

### 완전한 예시: 맥북 1개, 아이폰 2개 주문

#### Step 1: 사용자 장바구니 조회

**장바구니 내역:**
```json
[
  {
    "id": 1,
    "userId": 1,
    "productId": "macbook-uuid",
    "quantity": 1
  },
  {
    "id": 2,
    "userId": 1,
    "productId": "iphone-uuid",
    "quantity": 2
  }
]
```

---

#### Step 2: 상품 정보 조회

**상품 목록:**
```json
[
  {
    "id": "macbook-uuid",
    "name": "맥북 프로",
    "price": 3000000,
    "stock": 50,
    "isActive": true,
    "status": "AVAILABLE"
  },
  {
    "id": "iphone-uuid",
    "name": "아이폰 15",
    "price": 1500000,
    "stock": 100,
    "isActive": true,
    "status": "AVAILABLE"
  }
]
```

---

#### Step 3: 상품 검증

**맥북 프로:**
```
✅ 상품 존재
✅ 활성 상태 (isActive = true)
✅ 재고 충분 (stock 50 >= quantity 1)
```

**아이폰 15:**
```
✅ 상품 존재
✅ 활성 상태 (isActive = true)
✅ 재고 충분 (stock 100 >= quantity 2)
```

**재고 상태 (주문 후):**
```
맥북 프로: stock = 50 (변화 없음, 아직 차감 안함)
아이폰 15: stock = 100 (변화 없음, 아직 차감 안함)
```

---

#### Step 4: OrderItem 생성

**OrderItem 1 (맥북 프로):**
```json
{
  "productId": "macbook-uuid",
  "productName": "맥북 프로",
  "price": 3000000,
  "quantity": 1,
  "subtotal": 3000000
}
```

**OrderItem 2 (아이폰 15):**
```json
{
  "productId": "iphone-uuid",
  "productName": "아이폰 15",
  "price": 1500000,
  "quantity": 2,
  "subtotal": 3000000
}
```

---

#### Step 5: Order 금액 계산

**1차 계산 (쿠폰 적용 전):**
```
subTotal = 3000000 + 3000000 = 6000000
discountAmount = 0
totalAmount = 6000000 - 0 = 6000000
```

**쿠폰 할인 적용 (10% 할인 쿠폰):**
```
discountAmount = 6000000 * 0.1 = 600000
```

**2차 계산 (쿠폰 적용 후):**
```
subTotal = 6000000
discountAmount = 600000
totalAmount = 6000000 - 600000 = 5400000
```

---

#### Step 6: Order 저장

**저장된 Order:**
```json
{
  "id": "order-123-uuid",
  "userId": 1,
  "couponId": 123,
  "status": "PENDING",
  "subTotal": 6000000,
  "discountAmount": 600000,
  "totalAmount": 5400000,
  "orderItems": [
    {
      "id": 1,
      "productId": "macbook-uuid",
      "productName": "맥북 프로",
      "price": 3000000,
      "quantity": 1,
      "subtotal": 3000000
    },
    {
      "id": 2,
      "productId": "iphone-uuid",
      "productName": "아이폰 15",
      "price": 1500000,
      "quantity": 2,
      "subtotal": 3000000
    }
  ],
  "createdAt": "2025-01-15T10:00:00"
}
```

---

#### Step 7: Payment 자동 생성

**생성된 Payment:**
```json
{
  "id": "payment-456-uuid",
  "orderId": "order-123-uuid",
  "userId": 1,
  "amount": 5400000,
  "orderName": "맥북 프로 외 1건",
  "method": "CARD",
  "status": "PENDING",
  "paymentKey": null,
  "transactionKey": null,
  "createdAt": "2025-01-15T10:00:00"
}
```

---

#### Step 8: 최종 응답

**Response (OrderResponse):**
```json
{
  "orderId": "order-123-uuid",
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
      "quantity": 1,
      "subtotal": 3000000
    },
    {
      "productId": "iphone-uuid",
      "productName": "아이폰 15",
      "price": 1500000,
      "quantity": 2,
      "subtotal": 3000000
    }
  ],
  "payment": {
    "paymentId": "payment-456-uuid",
    "status": "PENDING",
    "amount": 5400000,
    "orderName": "맥북 프로 외 1건"
  },
  "createdAt": "2025-01-15T10:00:00"
}
```

---

## 주요 컴포넌트

### 1. OrderController
- **역할**: REST API 엔드포인트 제공
- **위치**: `interfaces/order/OrderController.java`
- **주요 메서드**:
  - `POST /orders` - 주문 생성

### 2. OrderApplicationService
- **역할**: 주문 비즈니스 로직 조율
- **위치**: `application/order/OrderApplicationService.java`
- **주요 메서드**:
  - `createOrder(userId, couponId)` - 주문 생성

### 3. Order (Domain)
- **역할**: 주문 도메인 모델
- **위치**: `domain/order/model/Order.java`
- **주요 메서드**:
  - `addOrderItem(orderItem)` - 주문 아이템 추가
  - `calculateAmounts()` - 금액 계산
  - `assignPayment(payment)` - 결제 연결

### 4. OrderItem (Domain)
- **역할**: 주문 아이템 도메인 모델
- **위치**: `domain/order/model/OrderItem.java`
- **주요 필드**:
  - `productId` - 상품 ID
  - `productName` - 주문 시점 상품명 (스냅샷)
  - `price` - 주문 시점 가격 (스냅샷)
  - `quantity` - 수량
  - `subtotal` - 소계 (price * quantity)

### 5. CouponIssueService
- **역할**: 쿠폰 할인 계산
- **위치**: `application/coupon/CouponIssueService.java`
- **주요 메서드**:
  - `calculateDiscount(couponId, userId, amount)` - 할인 금액 계산

---

## 재고 확인 vs 재고 차감

### 주문 생성 시점 (현재 문서)

**재고 확인만 수행:**
```java
if (product.getStock() < quantity) {
    throw new IllegalArgumentException("재고가 부족합니다.");
}
// ✅ 검증만 하고 재고는 차감하지 않음
```

**이유:**
1. 주문 생성과 결제는 별도 프로세스
2. 사용자가 주문 후 결제하지 않을 수 있음
3. 결제 실패 시 재고 복구 로직 불필요

---

### 결제 승인 시점 (PAYMENT_FLOW_GUIDE.md 참고)

**실제 재고 차감 수행:**
```java
// PaymentApplicationService.confirmPaymentForTest()
reduceProductStock(order);

// 내부 로직
product.decreaseStock(orderItem.getQuantity());
productRepository.save(product);
```

**이유:**
1. 결제 승인 = 실제 구매 확정
2. 이 시점부터 상품 재고 차감 필요
3. 결제 취소 시 재고 복구 로직 실행

---

### 시간 흐름별 재고 상태

**시나리오: 맥북 프로 재고 50개, 사용자 A가 1개 주문**

```
T1. 주문 생성
  - 재고 확인: ✅ (50 >= 1)
  - 재고 차감: ❌
  - 현재 재고: 50

T2. 결제 대기 중 (PENDING)
  - 현재 재고: 50 (다른 사용자도 주문 가능)

T3. 결제 승인 (confirmPayment)
  - 재고 차감: ✅
  - 현재 재고: 49

T4. 주문 확정 (CONFIRMED)
  - 현재 재고: 49
```

---

### 동시성 이슈 고려

**문제 시나리오:**
```
재고: 1개 남음

사용자 A: 주문 생성 (재고 확인 통과)
사용자 B: 주문 생성 (재고 확인 통과)

사용자 A: 결제 승인 (재고 차감 성공, 재고 0)
사용자 B: 결제 승인 (재고 차감 실패, 재고 부족 에러)
```

**현재 시스템:**
- 주문 생성은 재고 확인만 하므로 동시 주문 가능
- 결제 승인 시 실제 재고 차감 및 검증
- 결제 승인 실패 시 주문은 유효하지만 결제 실패 상태

**개선 방안 (향후 고려):**
1. 주문 생성 시 임시 재고 예약 (Redis)
2. 일정 시간 후 자동 해제
3. 결제 승인 시 실제 재고 차감

---

## 트랜잭션 및 일관성

### 트랜잭션 범위
```java
@Transactional
public Order createOrder(Long userId, Long couponId) {
    // 이 메서드 전체가 하나의 트랜잭션
    // 중간에 예외 발생 시 모든 변경사항 롤백
}
```

### 롤백 시나리오

**예외 발생 지점:**
1. 장바구니가 비어있음
2. 상품 정보를 찾을 수 없음
3. 상품이 비활성 상태
4. 재고가 부족
5. 쿠폰 계산 실패

**롤백 결과:**
- Order 저장 취소
- OrderItem 저장 취소
- Payment 저장 취소
- 데이터베이스 상태는 메서드 호출 전과 동일

---

## 다음 단계: 결제 승인

주문 생성 후 결제 승인 과정은 `PAYMENT_FLOW_GUIDE.md`를 참고하세요.

**결제 승인 시 발생하는 일:**
1. Payment 상태 변경 (PENDING → DONE)
2. Order 상태 변경 (PENDING → CONFIRMED)
3. **재고 차감** (실제 stock 감소)
4. TICKET 상품의 경우 Raffle 티켓 생성

---

## 참고 자료

- `backend/PAYMENT_FLOW_GUIDE.md` - 결제 승인 및 재고 차감 흐름
- `backend/CLAUDE.md` - 프로젝트 아키텍처 개요
