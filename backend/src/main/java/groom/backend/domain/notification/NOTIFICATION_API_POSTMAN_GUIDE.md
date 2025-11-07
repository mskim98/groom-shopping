# 알림 API Postman 테스팅 가이드

이 가이드는 DDD 방식으로 구현된 알림 관련 API들을 Postman으로 테스트하는 방법을 설명합니다.

---

## 📋 사전 준비사항

1. **Spring Boot 애플리케이션 실행 중**
2. **PostgreSQL 데이터베이스 실행 중** (Docker 컨테이너: `db`)
3. **Kafka 실행 중**
4. **Redis 실행 중**

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

## 📬 2단계: 사용자 알림 조회

### 2-1. 모든 알림 조회

#### Request 설정
- **Method**: `GET`
- **URL**: `http://localhost:8080/api/notifications`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`

#### 응답 예시
```json
[
  {
    "id": 1,
    "currentStock": 3,
    "thresholdValue": 2,
    "message": "재고가 3개로 얼마 남지 않았어요",
    "isRead": false,
    "createdAt": "2025-11-04T10:30:00",
    "userId": 10,
    "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758"
  },
  {
    "id": 2,
    "currentStock": 1,
    "thresholdValue": 2,
    "message": "재고가 1개로 얼마 남지 않았어요",
    "isRead": true,
    "createdAt": "2025-11-04T10:31:00",
    "userId": 10,
    "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758"
  }
]
```

---

### 2-2. 읽지 않은 알림만 조회

#### Request 설정
- **Method**: `GET`
- **URL**: `http://localhost:8080/api/notifications/unread`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`

#### 응답 예시
```json
[
  {
    "id": 1,
    "currentStock": 3,
    "thresholdValue": 2,
    "message": "재고가 3개로 얼마 남지 않았어요",
    "isRead": false,
    "createdAt": "2025-11-04T10:30:00",
    "userId": 10,
    "productId": "0ff5617a-e130-4deb-8568-6cc5d4cbd758"
  }
]
```

---

## ✅ 3단계: 알림 읽음 처리

### 3-1. 특정 알림 읽음 처리

#### Request 설정
- **Method**: `PATCH`
- **URL**: `http://localhost:8080/api/notifications/{notificationId}/read`
  - 예: `http://localhost:8080/api/notifications/1/read`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`

#### 응답
- **Status Code**: `204 No Content`
- **Body**: 없음

#### 확인 방법
읽음 처리 후 알림 조회 API를 다시 호출하면 `isRead: true`로 변경된 것을 확인할 수 있습니다.

---

### 3-2. 전체 알림 읽음 처리

#### Request 설정
- **Method**: `PATCH`
- **URL**: `http://localhost:8080/api/notifications/read-all`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`

#### 응답
- **Status Code**: `204 No Content`
- **Body**: 없음

#### 확인 방법
1. 읽지 않은 알림 조회 API (`GET /api/notifications/unread`)를 호출
2. 빈 배열 `[]`이 반환되면 모든 알림이 읽음 처리된 것입니다

---

## 🗑️ 4단계: 알림 삭제

### 4-1. 특정 알림 삭제

#### Request 설정
- **Method**: `DELETE`
- **URL**: `http://localhost:8080/api/notifications/{notificationId}`
  - 예: `http://localhost:8080/api/notifications/1`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`

#### 응답
- **Status Code**: `204 No Content`
- **Body**: 없음

#### 확인 방법
알림 조회 API를 다시 호출하면 삭제된 알림이 목록에서 제외된 것을 확인할 수 있습니다.

#### 보안 확인
- 본인의 알림만 삭제 가능합니다 (다른 사용자의 알림 ID를 입력해도 삭제되지 않음)
- 본인의 알림이 아닌 경우: 조용히 무시됨 (404 반환하지 않음 - 보안상의 이유)

---

### 4-2. 여러 알림 일괄 삭제

#### Request 설정
- **Method**: `DELETE`
- **URL**: `http://localhost:8080/api/notifications/batch`
- **Headers**: 
  - `Authorization: Bearer {TOKEN}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
```json
{
  "notificationIds": [1, 2, 3, 4, 5]
}
```

#### 응답
- **Status Code**: `204 No Content`
- **Body**: 없음

#### 확인 방법
1. 알림 조회 API를 호출
2. 삭제한 알림 ID들이 목록에서 제외된 것을 확인

#### 보안 확인
- 본인의 알림만 삭제됩니다
- 다른 사용자의 알림 ID가 포함되어 있어도 해당 알림은 삭제되지 않습니다
- 요청한 알림 중 본인 소유인 것만 삭제됩니다

---

## 📊 전체 테스트 시나리오

### 시나리오: 알림 관리 전체 플로우

1. **로그인**
   ```http
   POST /api/auth/login
   {"email": "admin@test.com", "password": "1234"}
   ```
   → `accessToken` 저장

2. **알림 조회 (초기 상태 확인)**
   ```http
   GET /api/notifications
   Authorization: Bearer {TOKEN}
   ```
   → 알림 목록 확인, `id` 값들 기록

3. **읽지 않은 알림만 조회**
   ```http
   GET /api/notifications/unread
   Authorization: Bearer {TOKEN}
   ```
   → 읽지 않은 알림만 확인

4. **특정 알림 읽음 처리**
   ```http
   PATCH /api/notifications/1/read
   Authorization: Bearer {TOKEN}
   ```
   → 알림 ID 1을 읽음 처리

5. **전체 알림 읽음 처리**
   ```http
   PATCH /api/notifications/read-all
   Authorization: Bearer {TOKEN}
   ```
   → 모든 알림을 읽음 처리

6. **읽지 않은 알림 조회 (확인)**
   ```http
   GET /api/notifications/unread
   Authorization: Bearer {TOKEN}
   ```
   → 빈 배열 `[]` 반환 확인

7. **특정 알림 삭제**
   ```http
   DELETE /api/notifications/1
   Authorization: Bearer {TOKEN}
   ```
   → 알림 ID 1 삭제

8. **여러 알림 일괄 삭제**
   ```http
   DELETE /api/notifications/batch
   Authorization: Bearer {TOKEN}
   Content-Type: application/json
   {"notificationIds": [2, 3, 4]}
   ```
   → 알림 ID 2, 3, 4 일괄 삭제

9. **알림 조회 (최종 확인)**
   ```http
   GET /api/notifications
   Authorization: Bearer {TOKEN}
   ```
   → 삭제된 알림들이 목록에서 제외된 것을 확인

---

## 🔍 API 엔드포인트 요약

| 기능 | Method | Endpoint | 설명 |
|------|--------|----------|------|
| 알림 목록 조회 | `GET` | `/api/notifications` | 사용자의 모든 알림 조회 |
| 읽지 않은 알림 조회 | `GET` | `/api/notifications/unread` | 읽지 않은 알림만 조회 |
| 특정 알림 읽음 처리 | `PATCH` | `/api/notifications/{id}/read` | 알림 ID로 읽음 처리 |
| 전체 알림 읽음 처리 | `PATCH` | `/api/notifications/read-all` | 모든 알림 읽음 처리 |
| 특정 알림 삭제 | `DELETE` | `/api/notifications/{id}` | 알림 ID로 삭제 |
| 여러 알림 일괄 삭제 | `DELETE` | `/api/notifications/batch` | 여러 알림 일괄 삭제 |

---

## 🛡️ 보안 및 권한

### 인증
- 모든 API는 `Authorization: Bearer {TOKEN}` 헤더가 필요합니다
- 토큰이 없거나 만료된 경우 `401 Unauthorized` 반환

### 권한
- 사용자는 자신의 알림만 조회/수정/삭제할 수 있습니다
- 다른 사용자의 알림 ID를 사용해도:
  - 조회: 빈 결과 반환
  - 읽음 처리: 무시됨 (에러 없음)
  - 삭제: 무시됨 (에러 없음)

---

## 📝 DDD 구조 설명

### Domain Layer (도메인 계층)
- **`Notification`** 엔티티: 알림 도메인 모델
  - `markAsRead()`: 읽음 처리 메서드
  - `create()`: 알림 생성 팩토리 메서드

### Application Layer (애플리케이션 계층)
- **`NotificationApplicationService`**: 알림 비즈니스 로직
  - `getNotifications()`: 알림 조회
  - `getUnreadNotifications()`: 읽지 않은 알림 조회
  - `markAsRead()`: 특정 알림 읽음 처리
  - `markAllAsRead()`: 전체 알림 읽음 처리
  - `deleteNotification()`: 알림 삭제
  - `deleteNotifications()`: 일괄 삭제

### Infrastructure Layer (인프라 계층)
- **`NotificationRepository`** 인터페이스: 도메인 리포지토리
- **`JpaNotificationRepository`**: JPA 구현체
- **`SpringDataNotificationRepository`**: Spring Data JPA 리포지토리

### Interface Layer (인터페이스 계층)
- **`NotificationController`**: REST API 엔드포인트
- **`NotificationResponse`**: 응답 DTO
- **`BatchDeleteNotificationRequest`**: 요청 DTO

---

## 🐛 문제 해결

### 알림이 조회되지 않는 경우
1. **토큰 확인**: 올바른 토큰을 사용하고 있는지 확인
2. **사용자 확인**: 로그인한 사용자에게 알림이 있는지 확인
   ```bash
   docker exec -it db psql -U dev -d shopping_db_dev -c "SELECT * FROM notifications WHERE user_id = 사용자ID;"
   ```

### 삭제가 안 되는 경우
1. **알림 소유권 확인**: 본인의 알림인지 확인
2. **알림 ID 확인**: 존재하는 알림 ID인지 확인
3. **로그 확인**: 애플리케이션 로그에서 `[NOTIFICATION_DELETE]` 확인

### 일괄 삭제가 부분적으로만 되는 경우
- 정상 동작입니다. 본인의 알림만 삭제되며, 다른 사용자의 알림 ID가 포함되어 있어도 해당 알림은 삭제되지 않습니다.

---

## ✅ 테스트 체크리스트

- [ ] 로그인 성공 및 토큰 획득
- [ ] 모든 알림 조회 성공
- [ ] 읽지 않은 알림 조회 성공
- [ ] 특정 알림 읽음 처리 성공
- [ ] 전체 알림 읽음 처리 성공
- [ ] 읽지 않은 알림 조회 시 빈 배열 반환 확인
- [ ] 특정 알림 삭제 성공
- [ ] 여러 알림 일괄 삭제 성공
- [ ] 삭제 후 알림 조회 시 해당 알림이 제외된 것 확인
- [ ] 다른 사용자의 알림 삭제 시도 시 무시되는 것 확인 (보안)

---

## 📌 참고사항

- **읽음 처리**: `isRead` 필드가 `true`로 변경됩니다
- **삭제**: 물리적 삭제(Soft Delete 아님)입니다
- **일괄 삭제**: 최대 개수 제한 없음 (필요시 프론트엔드에서 제한)
- **성능**: 일괄 삭제는 IN 쿼리를 사용하여 효율적으로 처리됩니다

---

**모든 API가 정상 작동하면 알림 관리 기능이 완성된 것입니다! 🎉**


