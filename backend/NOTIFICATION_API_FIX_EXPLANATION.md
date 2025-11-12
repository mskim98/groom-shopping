# 전체 알림 조회 API 500 에러 해결 과정

## 🔴 문제 발생
- **에러**: `HttpMessageConversionException: Type definition error: [simple type, class java.time.LocalDateTime]`
- **원인**: Jackson이 `LocalDateTime`을 JSON으로 직렬화하지 못함

## 🔍 원인 분석

### 1단계: Jackson 설정 누락
- Spring Boot는 기본적으로 `JavaTimeModule`을 자동 등록하지만, 
- 커스텀 `ObjectMapper` Bean이 있으면 기본 설정이 무시됨
- `RedisConfig`에 단순한 `ObjectMapper` Bean이 있어서 `LocalDateTime` 지원이 없었음

### 2단계: Bean 충돌
- `WebConfig`에 `@Primary ObjectMapper` 추가 시
- `RedisConfig`의 `objectMapper` Bean과 이름 충돌 발생
- Spring이 어떤 Bean을 사용할지 결정하지 못함

## ✅ 해결 과정

### 1. Jackson 설정 추가 (`application-dev.yml`)
```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false  # ISO-8601 형식으로 직렬화
```

### 2. WebConfig에 @Primary ObjectMapper 추가
```java
@Bean
@Primary
public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
    return builder
            .modules(new JavaTimeModule())  // LocalDateTime 지원
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
}
```

### 3. RedisConfig에서 중복 Bean 제거
- `RedisConfig`의 `objectMapper()` Bean 제거
- `@Primary` ObjectMapper가 자동으로 주입됨

## 📊 결과
- ✅ `LocalDateTime`이 ISO-8601 형식으로 정상 직렬화
- ✅ Bean 충돌 해결
- ✅ 전체 알림 조회 API 정상 동작

## 💡 핵심 포인트
1. **처음 안 된 이유**: Jackson이 `LocalDateTime`을 직렬화할 수 없었음
2. **지금 되는 이유**: `JavaTimeModule`이 등록된 `@Primary ObjectMapper`가 모든 곳에서 사용됨
