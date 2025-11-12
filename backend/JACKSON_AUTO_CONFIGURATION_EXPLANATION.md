# Jackson 자동 설정이 작동하는 이유

## ✅ 현재 상태
1. **RedisConfig의 objectMapper Bean 제거됨** ✅
2. **WebConfig의 @Primary ObjectMapper Bean 주석 처리됨** ✅
3. **application-dev.yml에 Jackson 설정 있음** ✅

## 🔍 Spring Boot 자동 설정 작동 원리

### 1. 커스텀 Bean이 없으면 자동 설정 사용
- Spring Boot는 `JacksonAutoConfiguration`이 자동으로 `ObjectMapper` Bean을 생성
- 기본적으로 `JavaTimeModule`이 자동 등록됨
- `application.yml`의 `spring.jackson.*` 설정을 읽어서 적용

### 2. application-dev.yml 설정이 적용됨
```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false  # ✅ 이 설정이 자동 설정에 적용됨
```

### 3. 왜 처음엔 안 됐나?
- `RedisConfig`에 단순한 `ObjectMapper` Bean이 있었음
- 커스텀 Bean이 있으면 **자동 설정이 무시됨**
- 그 Bean에는 `JavaTimeModule`이 없었음

## 📊 비교

### ❌ 처음 (안 됨)
```
RedisConfig.objectMapper() Bean 존재
  → Spring Boot 자동 설정 무시
  → JavaTimeModule 없음
  → LocalDateTime 직렬화 실패
```

### ✅ 지금 (됨)
```
커스텀 ObjectMapper Bean 없음
  → Spring Boot 자동 설정 사용
  → JavaTimeModule 자동 등록
  → application.yml 설정 적용
  → LocalDateTime 정상 직렬화
```

## 💡 결론
- **커스텀 Bean이 없으면 Spring Boot 자동 설정이 작동**
- **application.yml의 Jackson 설정만으로도 충분**
- **@Primary ObjectMapper Bean은 선택사항**
