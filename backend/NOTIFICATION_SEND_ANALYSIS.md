# createAndSendNotifications 동작 방식 분석

## 🔍 현재 구현 방식

### 순차 처리 (Sequential Processing)

현재 `createAndSendNotifications` 메서드는 **순차적으로 하나씩** 알림을 보냅니다.

```java
for (Long userId : userIds) {
    // 1. 알림 생성
    Notification notification = Notification.create(userId, productId, currentStock, thresholdValue);
    
    // 2. DB 저장 (동기)
    Notification saved = notificationRepository.save(notification);
    
    // 3. SSE 전송 (동기)
    sseService.sendNotification(userId, saved);
}
```

## 📊 동작 흐름

### 예시: 3명의 사용자에게 알림 전송

```
시작
  ↓
User1 처리 시작
  ├─ 알림 생성
  ├─ DB 저장 (완료 대기)
  ├─ SSE 전송 (완료 대기)
  └─ User1 완료 (예: 50ms)
  ↓
User2 처리 시작
  ├─ 알림 생성
  ├─ DB 저장 (완료 대기)
  ├─ SSE 전송 (완료 대기)
  └─ User2 완료 (예: 50ms)
  ↓
User3 처리 시작
  ├─ 알림 생성
  ├─ DB 저장 (완료 대기)
  ├─ SSE 전송 (완료 대기)
  └─ User3 완료 (예: 50ms)
  ↓
종료 (총 150ms)
```

### 시간 계산
- 사용자 1명당 약 50ms 소요
- 10명이면: 10 × 50ms = **500ms**
- 100명이면: 100 × 50ms = **5초**

## ⚙️ 현재 방식의 특징

### 장점
1. **단순성**: 구현이 간단하고 이해하기 쉬움
2. **트랜잭션 안전성**: 순차 처리로 트랜잭션 관리가 쉬움
3. **에러 처리**: 각 사용자별로 개별 에러 처리 가능

### 단점
1. **성능**: 사용자 수가 많을수록 시간이 오래 걸림
2. **확장성**: 대규모 사용자에게 알림 보낼 때 병목 발생

## 🔄 병렬 처리로 개선 가능

### 개선 방안 1: CompletableFuture 사용

```java
List<CompletableFuture<Void>> futures = userIds.stream()
    .map(userId -> CompletableFuture.runAsync(() -> {
        Notification notification = Notification.create(userId, productId, currentStock, thresholdValue);
        Notification saved = notificationRepository.save(notification);
        sseService.sendNotification(userId, saved);
    }))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

### 개선 방안 2: @Async 사용

```java
@Async
public CompletableFuture<Void> sendToUser(Long userId, UUID productId, Integer currentStock, Integer thresholdValue) {
    // 알림 생성 및 전송
}

// 호출부
List<CompletableFuture<Void>> futures = userIds.stream()
    .map(userId -> sendToUser(userId, productId, currentStock, thresholdValue))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

## 📈 성능 비교

### 현재 방식 (순차)
- 10명: ~500ms
- 100명: ~5초
- 1000명: ~50초

### 병렬 처리 (예상)
- 10명: ~50ms (10배 빠름)
- 100명: ~100ms (50배 빠름)
- 1000명: ~500ms (100배 빠름)

## 💡 결론

**현재는 순차적으로 하나씩 보내고 있습니다.**

- ✅ 사용자 수가 적을 때 (10명 이하): 충분히 빠름
- ⚠️ 사용자 수가 많을 때 (100명 이상): 병렬 처리 고려 필요

병렬 처리로 개선하려면 `CompletableFuture`나 `@Async`를 사용할 수 있습니다.

