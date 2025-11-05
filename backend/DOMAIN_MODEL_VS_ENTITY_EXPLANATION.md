# Domain Model vs Entity 차이 설명

## 🎯 핵심 차이점

### Entity (JPA 엔티티)
- **목적**: 데이터베이스 테이블과 1:1 매핑
- **위치**: Infrastructure Layer (인프라 계층)
- **특징**: JPA 어노테이션 사용 (`@Entity`, `@Table`, `@Column` 등)
- **의존성**: JPA/Hibernate에 의존적

### Domain Model (도메인 모델)
- **목적**: 비즈니스 로직과 규칙을 표현
- **위치**: Domain Layer (도메인 계층)
- **특징**: JPA 어노테이션 없음 (순수 Java 객체)
- **의존성**: 외부 프레임워크에 의존 없음

---

## 📊 실제 코드 비교

### 1. ProductJpaEntity (Entity) - 데이터베이스 매핑용

```java
@Entity                          // ← JPA 어노테이션
@Table(name = "product")         // ← 테이블 이름 지정
public class ProductJpaEntity {
    
    @Id                          // ← JPA 어노테이션
    @Column(columnDefinition = "uuid")
    private UUID id;
    
    @Column(name = "name")        // ← 컬럼 이름 지정
    private String name;          // ← 단순 String
    
    @Column(name = "price")
    private Integer price;        // ← 단순 Integer
    
    @Column(name = "stock")
    private Integer stock;        // ← 단순 Integer
    
    // getter, setter만 있음
    // 비즈니스 로직 없음
}
```

**특징:**
- 데이터베이스 구조를 그대로 반영
- 단순한 데이터 보관소
- 비즈니스 로직 없음

---

### 2. Product (Domain Model) - 비즈니스 로직용

```java
// JPA 어노테이션 없음!
public class Product {
    
    private UUID id;
    private Name name;              // ← Value Object (타입 안전)
    private Description description; // ← Value Object
    private Price price;             // ← Value Object
    private Stock stock;             // ← Value Object
    
    // 비즈니스 메서드들
    public void reduceStock(int quantity) {
        this.stock = this.stock.decrease(quantity);
        updateStatusByStock();
    }
    
    public boolean isStockBelowThreshold() {
        if (thresholdValue == null) return false;
        return this.stock.getAmount() <= this.thresholdValue;
    }
    
    public boolean canNotify() {
        return this.isActive != null && this.isActive
                && !this.stock.isEmpty()
                && isStockBelowThreshold();
    }
    
    // 상태 변경 로직
    private void updateStatusByStock() {
        if (this.stock.isEmpty()) {
            this.status = ProductStatus.OUT_OF_STOCK;
        } else {
            this.status = ProductStatus.AVAILABLE;
        }
    }
}
```

**특징:**
- 비즈니스 로직 포함 (재고 차감, 임계값 확인 등)
- Value Object 사용으로 타입 안전성 확보
- 데이터베이스와 독립적

---

## 🔄 Value Object 예시: Stock

### Domain Model의 Stock (Value Object)

```java
public class Stock {
    private Integer amount;
    
    public Stock(Integer amount) {
        validate(amount);  // 검증 로직
        this.amount = amount;
    }
    
    private void validate(Integer amount) {
        if (amount == null) {
            throw new IllegalArgumentException("수량은 필수 입니다.");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("수량은 0 이상이어야 합니다");
        }
    }
    
    public Stock decrease(Integer quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("감소할 수량은 양수여야 합니다.");
        }
        if (this.amount < quantity) {
            throw new IllegalArgumentException("재고가 부족합니다.");
        }
        return new Stock(this.amount - quantity);  // 불변 객체
    }
    
    public boolean isEmpty() {
        return this.amount == 0;
    }
}
```

**장점:**
- 타입 안전성: `Integer` 대신 `Stock` 타입 사용
- 자동 검증: 잘못된 값 생성 불가
- 비즈니스 규칙 캡슐화

---

## 📋 비교표

| 항목 | Entity (ProductJpaEntity) | Domain Model (Product) |
|------|---------------------------|------------------------|
| **목적** | DB 저장/조회 | 비즈니스 로직 |
| **위치** | Infrastructure Layer | Domain Layer |
| **어노테이션** | `@Entity`, `@Column` 등 | 없음 |
| **의존성** | JPA/Hibernate | 없음 |
| **데이터 타입** | `String`, `Integer` | `Name`, `Price`, `Stock` (Value Object) |
| **비즈니스 로직** | 없음 | 있음 (`reduceStock`, `isStockBelowThreshold` 등) |
| **검증** | 없음 | 생성자에서 검증 |
| **변경 가능성** | Setter로 자유롭게 변경 | 불변 객체 또는 제어된 변경 |

---

## 🎬 실제 사용 흐름

### 1. 데이터베이스에서 조회
```java
// 1. JPA Entity로 조회
ProductJpaEntity entity = springRepo.findById(id);

// 2. Domain Model로 변환
Product product = toDomain(entity);
// ProductJpaEntity → Product
// String name → Name (Value Object)
// Integer stock → Stock (Value Object)
```

### 2. 비즈니스 로직 실행
```java
// Domain Model에서 비즈니스 로직 실행
product.reduceStock(5);  // 재고 차감 + 상태 업데이트
boolean canNotify = product.canNotify();  // 알림 가능 여부 확인
```

### 3. 데이터베이스에 저장
```java
// 3. Domain Model을 JPA Entity로 변환
ProductJpaEntity entity = toEntity(product);
// Product → ProductJpaEntity
// Name name → String name
// Stock stock → Integer stock

// 4. 저장
springRepo.save(entity);
```

---

## 💡 왜 분리하는가?

### 1. 관심사의 분리 (Separation of Concerns)
- **Entity**: 데이터 저장/조회에만 집중
- **Domain Model**: 비즈니스 로직에만 집중

### 2. 테스트 용이성
```java
// Domain Model은 JPA 없이도 테스트 가능
Product product = Product.create(...);
product.reduceStock(5);
assertFalse(product.canNotify());
```

### 3. 유연성
- 데이터베이스를 변경해도 Domain Model은 변경 없음
- 비즈니스 로직 변경 시 Entity는 변경 없음

### 4. 타입 안전성
```java
// Entity: 타입 안전하지 않음
entity.setPrice(-100);  // 컴파일 에러 없음 (런타임에서만 문제)

// Domain Model: 타입 안전
Price price = new Price(-100);  // 생성자에서 검증 → IllegalArgumentException
```

---

## 🎯 요약

### Entity (ProductJpaEntity)
- **역할**: 데이터베이스와의 다리
- **특징**: JPA 어노테이션, 단순 데이터
- **비유**: 창고의 선반 (데이터 보관)

### Domain Model (Product)
- **역할**: 비즈니스 로직의 중심
- **특징**: 순수 Java, Value Object, 비즈니스 메서드
- **비유**: 창고 관리자 (데이터 처리 규칙)

**결론**: Entity는 "데이터를 어떻게 저장할까?", Domain Model은 "비즈니스 규칙은 무엇인가?"를 담당합니다.

