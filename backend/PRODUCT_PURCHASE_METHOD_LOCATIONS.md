# 제품 구매 관련 메서드 위치

## 📋 개요

제품 구매 기능은 3계층 구조로 구성되어 있습니다:
1. **Controller Layer** (인터페이스 계층) - HTTP 요청 처리
2. **Application Service Layer** (애플리케이션 계층) - 비즈니스 로직
3. **DTOs** (데이터 전송 객체) - 요청/응답 구조

---

## 📁 1. Controller Layer (인터페이스 계층)

### 파일 위치
```
src/main/java/groom/backend/interfaces/product/ProductController.java
```

### 메서드 정보
- **메서드명**: `purchaseProduct()`
- **엔드포인트**: `POST /api/products/purchase`
- **라인 번호**: 33-84
- **역할**: 
  - HTTP 요청 수신
  - 인증 정보 확인
  - body 유무에 따라 단일 제품 구매 또는 장바구니 전체 구매 분기
  - 응답 DTO 생성 및 반환

### 주요 로직
```java
@PostMapping("/purchase")
public ResponseEntity<?> purchaseProduct(
    @AuthenticationPrincipal CustomUserDetails userDetails,
    @RequestBody(required = false) PurchaseProductRequest request) {
    
    // body가 없으면 → 장바구니 전체 구매
    if (request == null || request.getProductId() == null) {
        productApplicationService.purchaseCartItems(userId);
    }
    
    // body가 있으면 → 단일 제품 구매
    else {
        productApplicationService.purchaseProduct(productId, quantity);
    }
}
```

---

## 📁 2. Application Service Layer (애플리케이션 계층)

### 파일 위치
```
src/main/java/groom/backend/application/product/ProductApplicationService.java
```

### 메서드 1: 단일 제품 구매

- **메서드명**: `purchaseProduct(UUID productId, Integer quantity)`
- **라인 번호**: 38-102
- **역할**: 
  - 제품 조회
  - 재고 차감
  - 임계값 확인 및 Kafka 이벤트 발행
  - 구매 결과 반환

#### 주요 처리 흐름
1. 제품 조회 (`productRepository.findById()`)
2. 재고 차감 (`product.reduceStock()`)
3. 제품 저장 (`productRepository.save()`)
4. 임계값 확인 및 Kafka 이벤트 발행 (비동기)
5. 구매 결과 반환

### 메서드 2: 장바구니 전체 구매

- **메서드명**: `purchaseCartItems(Long userId)`
- **라인 번호**: 110-182
- **역할**: 
  - 사용자의 장바구니 항목 조회
  - 각 항목을 순차적으로 구매
  - 구매 성공한 항목만 장바구니에서 제거
  - 구매 결과 리스트 반환

#### 주요 처리 흐름
1. 장바구니 항목 조회 (`cartItemRepository.findByUserId()`)
2. 각 항목에 대해 `purchaseProduct()` 호출
3. 구매 성공한 항목만 장바구니에서 제거
4. 구매 결과 리스트 반환

---

## 📁 3. DTOs (데이터 전송 객체)

### 요청 DTO
**파일**: `src/main/java/groom/backend/interfaces/product/dto/request/PurchaseProductRequest.java`

```java
public class PurchaseProductRequest {
    private UUID productId;
    private Integer quantity;
}
```

### 응답 DTO 1: 단일 제품 구매
**파일**: `src/main/java/groom/backend/interfaces/product/dto/response/PurchaseProductResponse.java`

```java
public class PurchaseProductResponse {
    private UUID productId;
    private Integer quantity;
    private Integer remainingStock;
    private Boolean stockThresholdReached;
    private String message;
}
```

### 응답 DTO 2: 장바구니 구매
**파일**: `src/main/java/groom/backend/interfaces/product/dto/response/PurchaseCartResponse.java`

```java
public class PurchaseCartResponse {
    private List<PurchaseItemResult> results;
    private Integer totalItems;
    private String message;
    
    public static class PurchaseItemResult {
        private UUID productId;
        private Integer quantity;
        private Integer remainingStock;
        private Boolean stockThresholdReached;
        private String message;
    }
}
```

---

## 🔄 호출 흐름

```
클라이언트 요청
    ↓
ProductController.purchaseProduct()
    ↓
ProductApplicationService.purchaseProduct() 또는 purchaseCartItems()
    ↓
ProductRepository.findById()
    ↓
Product.reduceStock()
    ↓
ProductRepository.save()
    ↓
StockThresholdProducer.publishStockThresholdEvent() (비동기)
    ↓
응답 반환
```

---

## 📊 메서드 관계도

```
ProductController
    ├─ purchaseProduct()
    │   ├─ purchaseProduct(productId, quantity) → 단일 제품 구매
    │   └─ purchaseCartItems(userId) → 장바구니 전체 구매
    │
ProductApplicationService
    ├─ purchaseProduct(UUID productId, Integer quantity)
    │   ├─ ProductRepository.findById()
    │   ├─ Product.reduceStock()
    │   ├─ ProductRepository.save()
    │   └─ StockThresholdProducer.publishStockThresholdEvent()
    │
    └─ purchaseCartItems(Long userId)
        ├─ CartItemRepository.findByUserId()
        ├─ purchaseProduct() 반복 호출
        └─ CartItemRepository.delete() (성공한 항목만)
```

---

## 🎯 주요 기능

1. **단일 제품 구매**
   - 제품 ID와 수량으로 구매
   - 재고 차감 및 Kafka 이벤트 발행

2. **장바구니 전체 구매**
   - 사용자의 장바구니에 있는 모든 제품 구매
   - 각 제품을 순차적으로 구매 처리
   - 구매 성공한 항목만 장바구니에서 제거

3. **재고 관리**
   - 구매 시 재고 자동 차감
   - 임계값 도달 시 Kafka 이벤트 발행

4. **성능 로깅**
   - 각 단계별 처리 시간 측정 및 로깅
   - Kafka 비동기 처리 성능 측정

---

## 📝 참고사항

- 모든 구매 메서드는 `@Transactional`로 트랜잭션 관리
- Kafka 이벤트 발행은 비동기로 처리되어 API 응답 시간에 영향 없음
- 장바구니 구매는 실패한 항목이 있어도 성공한 항목은 처리됨

