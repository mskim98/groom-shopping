# Repository 구조 설명: ProductQueryRepository는 어떻게 작동하는가?

## 🤔 질문: ProductQueryRepository에 extends가 없는데 어떻게 작동하나?

**답변**: 실제로는 **SpringDataProductRepository**가 `JpaRepository`를 extends하고, `ProductJpaEntity`와 매핑됩니다!

---

## 📊 전체 구조

### 1. Domain Layer (인터페이스)

```java
// ProductQueryRepository.java
// ❌ extends 없음 - 순수 인터페이스
public interface ProductQueryRepository {
    Page<Product> findAll(Pageable pageable);
    Optional<Product> findById(UUID id);
}
```

**특징:**
- JPA 의존성 없음
- Domain Model(`Product`)만 다룸
- 순수 인터페이스

---

### 2. Infrastructure Layer (구현체)

```java
// JpaProductQueryRepository.java
@Repository
public class JpaProductQueryRepository implements ProductQueryRepository {
    
    // ✅ 실제 JPA Repository 주입
    private final SpringDataProductRepository springDataProductRepository;
    
    @Override
    public Page<Product> findAll(Pageable pageable) {
        // 1. ProductJpaEntity로 조회 (JPA 사용)
        return springDataProductRepository.findAll(pageable)
                // 2. ProductJpaEntity → Product 변환
                .map(e -> jpaProductRepository.toDomain(e));
    }
}
```

**특징:**
- `@Repository` 어노테이션으로 Spring Bean 등록
- `SpringDataProductRepository`를 사용하여 실제 DB 접근
- Entity ↔ Domain Model 변환 담당

---

### 3. Spring Data JPA Repository (실제 DB 접근)

```java
// SpringDataProductRepository.java
// ✅ 여기서 JpaRepository를 extends!
public interface SpringDataProductRepository 
    extends JpaRepository<ProductJpaEntity, UUID> {
    
    List<ProductJpaEntity> findByIdIn(List<UUID> ids);
}
```

**특징:**
- `JpaRepository<ProductJpaEntity, UUID>` extends
- `ProductJpaEntity`와 매핑 (실제 엔티티)
- Spring Data JPA가 자동으로 구현체 생성

---

## 🔄 동작 흐름

### 예시: `findAll()` 호출

```
1. Service Layer
   ↓
   productQueryRepository.findAll(pageable)
   
2. Infrastructure Layer (JpaProductQueryRepository)
   ↓
   springDataProductRepository.findAll(pageable)
   // ProductJpaEntity 조회 (JPA 사용)
   
3. Spring Data JPA
   ↓
   SELECT * FROM product LIMIT 10 OFFSET 0
   // 실제 SQL 실행
   
4. 변환
   ↓
   ProductJpaEntity → Product (Domain Model)
   // toDomain() 메서드 사용
   
5. 반환
   ↓
   Page<Product> (Domain Model)
```

---

## 📋 계층별 역할

### Domain Layer
```java
ProductQueryRepository (인터페이스)
```
- **역할**: 도메인 계층의 계약 정의
- **의존성**: 없음 (순수 Java)

### Infrastructure Layer
```java
JpaProductQueryRepository (구현체)
  ↓ 사용
SpringDataProductRepository (JpaRepository)
  ↓ 매핑
ProductJpaEntity (@Entity)
```
- **역할**: 실제 DB 접근 및 변환
- **의존성**: JPA, Spring Data JPA

---

## 💡 핵심 포인트

### 1. ProductQueryRepository는 인터페이스일 뿐
- `extends`가 없어도 됩니다
- 실제 구현은 `JpaProductQueryRepository`에서 합니다

### 2. 실제 JPA 작업은 SpringDataProductRepository가 담당
```java
// SpringDataProductRepository가 extends
extends JpaRepository<ProductJpaEntity, UUID>
                          ↑
                    실제 엔티티!
```

### 3. 변환 과정
```
ProductJpaEntity (DB) 
    ↓ toDomain()
Product (Domain Model)
```

---

## 🎯 구조 요약

```
┌─────────────────────────────────┐
│ Domain Layer                    │
│ ProductQueryRepository          │  ← 인터페이스 (extends 없음)
└──────────────┬──────────────────┘
               │ implements
               ↓
┌─────────────────────────────────┐
│ Infrastructure Layer            │
│ JpaProductQueryRepository      │  ← 구현체 (@Repository)
│   ↓ 의존성 주입                 │
│ SpringDataProductRepository    │  ← JpaRepository extends!
│   ↓ 매핑                        │
│ ProductJpaEntity (@Entity)     │  ← 실제 엔티티
└─────────────────────────────────┘
```

---

## ✅ 결론

**ProductQueryRepository에 extends가 없어도 작동하는 이유:**

1. **ProductQueryRepository**는 단순 인터페이스 (계약 정의)
2. **JpaProductQueryRepository**가 구현 (실제 로직)
3. **SpringDataProductRepository**가 `JpaRepository`를 extends (실제 JPA)
4. **ProductJpaEntity**가 실제 엔티티 (`@Entity`)

**즉, ProductQueryRepository는 "도메인 계층의 계약"이고, 실제 JPA 작업은 Infrastructure Layer에서 처리합니다!**

---

## 📝 DDD 원칙

이 구조는 DDD의 **Dependency Inversion Principle**을 따릅니다:

- **Domain Layer**: 추상화 (인터페이스)
- **Infrastructure Layer**: 구체화 (구현체)

Domain Layer는 Infrastructure Layer에 의존하지 않고, Infrastructure Layer가 Domain Layer를 구현합니다!

