# Flyway 마이그레이션 Troubleshooting 가이드

## 개요

이 문서는 Spring Boot + Hibernate + Flyway 환경에서 발생할 수 있는 스키마 관리 문제들을 해결하기 위한 가이드입니다.

---

## 문제 1: Mock 데이터가 데이터베이스에 삽입되지 않음

### 증상
- Flyway V2, V3 마이그레이션 파일에 INSERT 문이 있지만 데이터가 들어가지 않음
- 테스트 유저, 상품 등이 데이터베이스에 없음

### 원인
```yaml
# ❌ 잘못된 설정
flyway:
  enabled: false  # Flyway 비활성화
jpa:
  hibernate:
    ddl-auto: update  # 테이블 구조만 생성
```

- **Flyway 비활성화**: 마이그레이션 파일들(V1, V2, V3)이 실행되지 않음
- **ddl-auto: update**: INSERT 문은 실행하지 않고 테이블 구조만 생성

### 해결책

```yaml
# ✅ 올바른 설정
flyway:
  enabled: true  # Flyway 활성화하여 모든 마이그레이션 파일 실행

jpa:
  hibernate:
    ddl-auto: none  # Flyway가 스키마 관리하므로 Hibernate는 관리하지 않음
```

**주의:** Mock 데이터를 자동으로 삽입하려면 반드시 `flyway.enabled: true`로 설정해야 합니다.

---

## 문제 2: Schema-validation 에러 (테이블/컬럼명 불일치)

### 증상

```
Schema-validation: missing table [`order`]
Schema-validation: missing table [Coupon]
Schema-validation: missing column [expireDate] in table [Coupon]
```

### 원인

Hibernate의 **PhysicalNamingStrategy**가 엔티티명/컬럼명을 데이터베이스 테이블명으로 변환하는 방식이 마이그레이션 파일과 일치하지 않는 경우:

```java
// Order.java
@Entity
@Table(name = "\"Order\"")  // 명시적으로 대문자로 지정
public class Order {
    @Column(name = "userId")  // camelCase
    private Long userId;
}
```

```sql
-- V1__Initial_schema.sql
CREATE TABLE IF NOT EXISTS "order" (  -- ❌ 소문자
    user_id BIGINT,  -- ❌ snake_case
    ...
);
```

**Hibernate 기본 명명 전략**: `SpringPhysicalNamingStrategy`
- `Order` → `order` (소문자)
- `userId` → `user_id` (snake_case)

따라서 엔티티와 Flyway 마이그레이션 파일의 **테이블/컬럼명이 정확히 일치**해야 합니다.

### 해결책

#### Option A: 엔티티 기준으로 Flyway 통일 (권장)

엔티티 정의를 따르는 PhysicalNamingStrategy 사용:

```yaml
jpa:
  hibernate:
    naming:
      physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
```

이 전략은 엔티티의 `@Table`과 `@Column`에서 명시적으로 지정한 이름을 그대로 사용합니다.

**V1 마이그레이션 파일 수정:**
```sql
-- Order 엔티티: @Table(name = "\"Order\""), @Column(name = "userId")
CREATE TABLE IF NOT EXISTS "Order" (
    id UUID PRIMARY KEY,
    "userId" BIGINT NOT NULL,
    "subTotal" INTEGER NOT NULL,
    ...
);

-- Coupon 엔티티: @Entity (명시적 @Table 없음)
-- → PhysicalNamingStrategyStandardImpl 사용 시 "Coupon" 테이블명 기대
CREATE TABLE IF NOT EXISTS "Coupon" (
    id BIGSERIAL PRIMARY KEY,
    "isActive" BOOLEAN DEFAULT TRUE,
    "expireDate" DATE,
    ...
);
```

#### Option B: 마이그레이션 기준으로 엔티티 통일

모든 엔티티를 snake_case로 통일하고, 기본 `SpringPhysicalNamingStrategy` 사용:

```java
@Entity
@Table(name = "order")  // 소문자
public class Order {
    @Column(name = "user_id")  // snake_case
    private Long userId;
}
```

```sql
-- V1__Initial_schema.sql
CREATE TABLE IF NOT EXISTS order (
    user_id BIGINT NOT NULL,
    ...
);
```

---

## 문제 3: Hibernate ddl-auto: validate 모드에서 검증 실패

### 증상

```
Unable to build Hibernate SessionFactory; nested exception is
org.hibernate.tool.schema.spi.SchemaManagementException:
Schema-validation: missing table [Coupon]
```

테이블이 데이터베이스에 있음에도 불구하고 Hibernate가 찾지 못함.

### 원인

```yaml
jpa:
  hibernate:
    ddl-auto: validate  # 스키마 검증만 수행
```

`validate` 모드는:
1. 엔티티 메타데이터를 읽음
2. 데이터베이스의 테이블/컬럼과 비교
3. 불일치하면 예외 발생

Flyway가 마이그레이션을 수행한 후에 Hibernate가 검증을 시도할 때, **PhysicalNamingStrategy의 테이블명 변환 규칙**과 실제 데이터베이스 테이블명이 일치하지 않으면 검증 실패.

### 해결책

```yaml
jpa:
  hibernate:
    ddl-auto: none  # Flyway가 전담하므로 Hibernate는 관리 안 함
```

**ddl-auto 옵션 설정 가이드:**

| 옵션 | 용도 | 상황 |
|------|------|------|
| `none` | 아무것도 하지 않음 | ✅ **Flyway 사용 시 (권장)** |
| `validate` | 스키마 검증만 | ⚠️ Flyway + 정확한 명명 규칙 필요 |
| `update` | 엔티티 기반 스키마 자동 생성/수정 | ❌ Flyway와 함께 사용 금지 |
| `create` | 매 시작마다 스키마 삭제 후 생성 | ❌ 개발 환경에서도 위험 |
| `create-drop` | 시작 시 생성, 종료 시 삭제 | ❌ 테스트 환경 전용 |

---

## 문제 4: Flyway 마이그레이션 checksum 오류

### 증상

```
Validate failed: Migrations have failed validation
FlywayValidateException: Validate failed: Migrations have failed validation
```

마이그레이션 파일을 수정한 후 실행 시 발생.

### 원인

Flyway는 실행된 마이그레이션의 checksum을 `flyway_schema_history` 테이블에 저장합니다. 이미 실행된 마이그레이션 파일을 수정하면 checksum이 변경되어 검증 오류 발생.

```sql
-- flyway_schema_history 테이블
SELECT * FROM flyway_schema_history;

-- 결과:
-- version | description      | checksum (변경 전)
-- 1       | Initial schema   | 308449172
-- (파일 수정 후)
-- checksum이 변경되어 검증 실패
```

### 해결책

#### 방법 1: 데이터베이스 초기화 (개발 환경)

```bash
# PostgreSQL 접속
docker exec dev-db psql -U dev -d postgres -c "DROP DATABASE shopping_db_dev;"
docker exec dev-db psql -U dev -d postgres -c "CREATE DATABASE shopping_db_dev OWNER dev;"

# 애플리케이션 재시작
./gradlew bootRun
```

**주의:** 개발 환경에서만 사용 가능. 기존 데이터가 모두 삭제됩니다.

#### 방법 2: Flyway clean 기능 (초기 1회만)

```yaml
flyway:
  enabled: true
  clean-disabled: false  # 초기 시작 시 스키마 전체 삭제 후 마이그레이션 실행
```

**주의:**
1. 개발 환경에서만 사용
2. 초기 설정 후 반드시 `clean-disabled: false` 제거
3. 프로덕션에서는 절대 사용 금지

```yaml
# ❌ 금지 - clean-disabled: false를 제거해야 함
flyway:
  clean-disabled: false
```

#### 방법 3: Flyway 메타데이터 직접 수정 (권장 안 함)

```sql
-- flyway_schema_history에서 문제 마이그레이션 삭제
DELETE FROM flyway_schema_history WHERE version = 1;

-- 또는 checksum 업데이트 (Flyway 내부 구조 이해 필요)
UPDATE flyway_schema_history SET checksum = NULL WHERE version = 1;
```

**주의:** Flyway 내부 메커니즘을 깨뜨릴 수 있으므로 권장하지 않음.

---

## 문제 5: 엔티티와 Flyway 마이그레이션 동기화 불일치

### 증상

- 엔티티에 새로운 필드 추가했는데 데이터베이스에 컬럼이 없음
- 엔티티 필드를 삭제했는데 데이터베이스 컬럼이 여전히 있음

### 원인

엔티티와 Flyway 마이그레이션 파일이 별도로 관리되므로 **개발자가 수동으로 동기화**해야 함.

```java
// Order.java에 필드 추가
@Column(name = "notes")
private String notes;
```

```sql
-- V1__Initial_schema.sql을 수정하지 않으면
-- → 데이터베이스에 notes 컬럼이 없음
```

### 해결책

#### 방법 1: 새로운 마이그레이션 파일 생성 (권장)

```sql
-- V4__Add_order_notes_column.sql
ALTER TABLE "Order" ADD COLUMN notes VARCHAR(500);
CREATE INDEX idx_order_notes ON "Order"(notes);
```

**장점:**
- 마이그레이션 히스토리 추적 가능
- 롤백 가능 (새 마이그레이션 추가로 해제)
- 프로덕션 배포 시 안전

**단점:**
- 마이그레이션 파일이 증가

#### 방법 2: 개발 환경에서만 V1 수정 (개발 초기 단계)

```sql
-- V1__Initial_schema.sql 수정
CREATE TABLE IF NOT EXISTS "Order" (
    ...
    notes VARCHAR(500),
    ...
);
```

**조건:**
- 개발 환경에서만 사용
- 프로덕션 배포 전에는 불가능
- 데이터베이스 재초기화 필수

**과정:**
```bash
# 데이터베이스 초기화
docker exec dev-db psql -U dev -d postgres -c "DROP DATABASE shopping_db_dev;"
docker exec dev-db psql -U dev -d postgres -c "CREATE DATABASE shopping_db_dev OWNER dev;"

# 애플리케이션 재시작
./gradlew bootRun
```

---

## 체크리스트: Flyway 올바른 설정

### 1. application-dev.yml 설정

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none  # ✅ Flyway가 스키마 관리
      naming:
        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
    properties:
      hibernate:
        format_sql: true
        show_sql: true

  flyway:
    enabled: true  # ✅ Flyway 활성화
    # clean-disabled: false는 초기 설정 후 반드시 제거!
```

### 2. 엔티티와 V1 마이그레이션 동기화

- [ ] 모든 엔티티의 `@Table(name = "...")` 확인
- [ ] 모든 엔티티의 `@Column(name = "...")` 확인
- [ ] V1 마이그레이션에서 테이블명이 일치
- [ ] V1 마이그레이션에서 컬럼명이 일치
- [ ] PhysicalNamingStrategy와 실제 컬럼명 동기화

### 3. 데이터 검증

```bash
# 테스트 데이터 확인
docker exec dev-db psql -U dev -d shopping_db_dev -c "SELECT COUNT(*) FROM users;"
docker exec dev-db psql -U dev -d shopping_db_dev -c "SELECT COUNT(*) FROM product;"

# 테이블 구조 확인
docker exec dev-db psql -U dev -d shopping_db_dev -c "\d \"Order\""
```

### 4. 애플리케이션 시작 확인

```bash
./gradlew bootRun

# 로그 확인
# - "Successfully applied 3 migrations" (V1, V2, V3)
# - "Started BackendApplication"
# - 에러 메시지 없음
```

---

## 마이그레이션 파일 작성 가이드

### 파일명 규칙

```
V{번호}__{설명}.sql

예:
V1__Initial_schema.sql
V2__Add_test_users.sql
V3__Add_test_products.sql
V4__Add_order_notes_column.sql
```

- 번호는 1부터 순차적으로
- 번호와 설명 사이 언더스코어 2개 (`__`)
- 파일명은 대소문자 구분 (Linux에서 중요)

### SQL 작성 팁

```sql
-- ✅ 좋은 예
CREATE TABLE IF NOT EXISTS "Order" (
    id UUID PRIMARY KEY,
    "userId" BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_user FOREIGN KEY ("userId") REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_order_user_id ON "Order"("userId");

-- ❌ 나쁜 예
CREATE TABLE Order (  -- 테이블명 대소문자 모호
    userId BIGINT,  -- 컬럼명 quoted 없음 (PostgreSQL은 소문자로 변환)
    created_at TIMESTAMP
);
```

### PostgreSQL 대소문자 처리

PostgreSQL은 **quoted identifier가 대소문자 구분, unquoted는 소문자로 변환**:

```sql
CREATE TABLE "Order" (...);  -- ✅ Order 테이블 생성
CREATE TABLE Order (...);    -- ❌ order 테이블 생성 (소문자로 변환)

SELECT * FROM "Order";       -- ✅ OK
SELECT * FROM Order;         -- ❌ order 테이블을 찾음
```

---

## 자주하는 실수

### 1. Flyway와 Hibernate ddl-auto 동시 사용

```yaml
# ❌ 금지
flyway:
  enabled: true
jpa:
  hibernate:
    ddl-auto: update  # 충돌 가능!
```

**문제:** 두 도구가 동시에 스키마를 관리하려고 해서 예측 불가능한 결과 발생.

**올바른 설정:** 하나의 도구만 선택
```yaml
# Option 1: Flyway 사용 (권장)
flyway:
  enabled: true
jpa:
  hibernate:
    ddl-auto: none

# Option 2: Hibernate 사용
flyway:
  enabled: false
jpa:
  hibernate:
    ddl-auto: update
```

### 2. 마이그레이션 파일 수정 후 재배포

```bash
# ❌ 이미 실행된 V1을 수정하고 배포
git commit -am "Fix V1__Initial_schema.sql"
git push

# → Flyway checksum 오류 발생!
```

**올바른 방법:** 새로운 마이그레이션 파일 생성
```bash
# V2__Fix_order_table.sql 생성
git add V2__Fix_order_table.sql
git commit -m "Add V2 migration to fix Order table"
git push
```

### 3. 프로덕션에서 데이터베이스 재초기화

```bash
# ❌ 절대 금지!
docker exec prod-db psql -U prod -d shopping_db_prod -c "DROP DATABASE shopping_db_prod;"
```

**이유:** 모든 데이터가 삭제되고 서비스가 중단됨.

**프로덕션 마이그레이션:** 항상 새로운 마이그레이션 파일 추가

---

## 참고 자료

- [Spring Boot Flyway 공식 문서](https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.data-initialization.migration-tool.flyway)
- [Flyway 마이그레이션 가이드](https://flywaydb.org/documentation/database/postgresql)
- [Hibernate 명명 전략](https://hibernate.org/orm/documentation/)
- [PostgreSQL 식별자 규칙](https://www.postgresql.org/docs/current/sql-lexical.html#SQL-LEXICAL-IDENTIFIERS)

---

## 빠른 문제 해결 플로우

```
문제 발생
  ↓
1. 에러 메시지 확인
   ├─ "missing table" → 문제 2 참고
   ├─ "missing column" → 문제 2 참고
   ├─ "Validate failed" → 문제 4 참고
   └─ "Mock data 없음" → 문제 1 참고
  ↓
2. application-dev.yml 확인
   ├─ flyway.enabled: true? → Yes: 다음
   ├─ ddl-auto: none? → Yes: 다음
   └─ naming.physical-strategy 설정? → Yes: 다음
  ↓
3. 엔티티와 V1 마이그레이션 비교
   ├─ 테이블명 일치? → No: 수정
   ├─ 컬럼명 일치? → No: 수정
   └─ 컬럼타입 일치? → No: 수정
  ↓
4. 데이터베이스 초기화
   $ docker exec dev-db psql -U dev -d postgres \
     -c "DROP DATABASE shopping_db_dev; \
          CREATE DATABASE shopping_db_dev OWNER dev;"
  ↓
5. 애플리케이션 재시작
   $ ./gradlew clean build -x test
   $ ./gradlew bootRun
  ↓
정상 작동!
```
