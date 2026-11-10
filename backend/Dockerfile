# 1단계: Gradle 로 실행 jar 를 만든다 (시험은 DB · Redis · Kafka 가 필요하므로 이미지 빌드에서는 돌리지 않는다)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 의존성만 먼저 받아 소스가 바뀌어도 이 층을 다시 쓴다
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && mv build/libs/*-SNAPSHOT.jar /src/app.jar

# 2단계: JRE 만 담은 실행 이미지. 키 · 비밀번호는 이미지에 넣지 않고 기동 때 환경변수와 마운트로 받는다
FROM eclipse-temurin:21-jre
# /var/lib/streams : 컨테이너별 Kafka Streams 상태 볼륨의 마운트 자리 - 소유자를 미리 app 으로 둬야 이름 있는 볼륨이 쓰기 가능하게 만들어진다
RUN useradd --system --create-home --uid 10001 app \
    && mkdir -p /app /var/lib/streams && chown app /app /var/lib/streams
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
