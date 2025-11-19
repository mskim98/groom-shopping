# 문제 해결 가이드

## 백엔드 빌드가 계속 실행되는 문제

### 증상
백엔드 서비스를 제외했는데도 Docker Compose가 백엔드를 빌드하려고 시도합니다.

### 원인
1. 이전에 실행한 빌드 프로세스가 아직 실행 중
2. Docker 빌드 캐시가 남아있음
3. 다른 docker-compose 파일이 실행 중

### 해결 방법

#### 1. 실행 중인 빌드 중단
```bash
# Ctrl+C로 현재 빌드 중단
# 또는 다른 터미널에서:
docker ps -a | grep build | awk '{print $1}' | xargs docker stop
```

#### 2. 모든 컨테이너 중지 및 제거
```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod down
docker ps -a | awk '{print $1}' | xargs docker rm -f 2>/dev/null || true
```

#### 3. 빌드 캐시 정리 (선택사항)
```bash
# 백엔드 빌드 캐시만 삭제
docker builder prune --filter "label=service=backend" -f

# 또는 모든 빌드 캐시 삭제
docker builder prune -a -f
```

#### 4. 프론트엔드만 명시적으로 빌드
```bash
# 프론트엔드만 빌드
docker compose -f docker-compose.prod.yml --env-file .env.prod build frontend

# 서비스 시작 (이미 빌드된 이미지 사용)
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

#### 5. 특정 서비스만 시작
```bash
# 프론트엔드와 nginx만 시작
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d frontend nginx

# 데이터베이스 등도 필요한 경우
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d db redis zookeeper kafka frontend nginx
```

## 빠른 해결 (권장)

EC2에서 다음 명령어를 순서대로 실행:

```bash
# 1. 모든 컨테이너 중지
docker compose -f docker-compose.prod.yml --env-file .env.prod down

# 2. 실행 중인 모든 컨테이너 강제 중지
docker ps -q | xargs -r docker stop

# 3. 프론트엔드만 빌드
docker compose -f docker-compose.prod.yml --env-file .env.prod build frontend

# 4. 필요한 서비스만 시작
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d frontend nginx db redis zookeeper kafka
```

## 확인 방법

```bash
# 빌드 중인 프로세스 확인
docker ps

# 빌드 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f frontend

# 실행 중인 서비스 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

