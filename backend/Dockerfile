# backend/Dockerfile (최종 권장 버전)
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# 필요한 패키지 설치 (캐시 효율성을 위해)
RUN apt-get update && apt-get install -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Gradle 파일들 먼저 복사 (캐시 최적화)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# 의존성 다운로드 (소스 변경 시 캐시 활용)
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon

# 소스 코드 복사
COPY src src

# 애플리케이션 빌드
RUN ./gradlew clean build -x test --no-daemon

# 실행 스테이지
FROM eclipse-temurin:21-jre-jammy

# 보안을 위한 사용자 생성
RUN groupadd -r spring && useradd -r -g spring spring

WORKDIR /app

# JAR 파일 복사
COPY --from=builder --chown=spring:spring /app/build/libs/*.jar app.jar

# 헬스체크 추가
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 필요한 패키지 설치 (curl for healthcheck)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Xms512m", \
    "-Xmx1024m", \
    "-jar", \
    "app.jar"]