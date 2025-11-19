# npm 설치 타임아웃 문제 해결

## 문제
EC2에서 Docker 빌드 시 `npm ci` 또는 `npm install`이 멈추거나 타임아웃되는 경우

## 즉시 해결 방법

### 1. 현재 빌드 중단
```bash
# Ctrl+C로 중단
# 또는 다른 터미널에서:
docker ps -q | xargs docker stop 2>/dev/null || true
```

### 2. 네트워크 연결 확인
```bash
# npm 레지스트리 접근 테스트
curl -v --max-time 10 https://registry.npmjs.org/

# DNS 확인
nslookup registry.npmjs.org

# 연결 속도 테스트
time curl -o /dev/null https://registry.npmjs.org/
```

### 3. EC2에서 직접 npm 설치 테스트
```bash
# EC2에 Node.js가 설치되어 있다면
cd ~/groom-shopping4/frontend
npm install --dry-run

# 또는 실제 설치 테스트
npm install
```

### 4. Docker 빌드 시 타임아웃 증가
```bash
# Docker 빌드 타임아웃 설정 (기본값보다 길게)
DOCKER_BUILDKIT=1 docker compose -f docker-compose.prod.yml --env-file .env.prod build \
  --progress=plain \
  --no-cache \
  frontend
```

### 5. 단계별 빌드 (디버깅용)
```bash
# 1단계: 베이스 이미지 확인
docker run -it --rm node:20 bash

# 2단계: npm 설정 테스트
docker run -it --rm node:20 npm config list

# 3단계: npm 레지스트리 접근 테스트
docker run -it --rm node:20 npm view react version
```

## Dockerfile 수정 사항

현재 Dockerfile은 다음과 같이 수정되었습니다:
- `npm ci` 대신 `npm install` 사용 (더 관대함)
- 재시도 횟수 증가 (10회)
- 타임아웃 시간 증가 (최대 5분)
- 여러 번 재시도 로직 추가

## 대안: 로컬에서 빌드 후 이미지 업로드

EC2 네트워크가 불안정한 경우:

### 1. 로컬에서 이미지 빌드
```bash
# 로컬에서
cd frontend
docker build -t frontend:latest .

# 이미지 저장
docker save frontend:latest | gzip > frontend-image.tar.gz
```

### 2. EC2로 이미지 전송
```bash
# 로컬에서
scp -i your-key.pem frontend-image.tar.gz ubuntu@your-ec2-ip:~/
```

### 3. EC2에서 이미지 로드
```bash
# EC2에서
gunzip -c frontend-image.tar.gz | docker load
docker tag frontend:latest groom-shopping4-frontend:latest
```

## 네트워크 최적화

### npm 레지스트리 미러 사용
```bash
# .npmrc 파일 생성
cat > frontend/.npmrc << EOF
registry=https://registry.npmjs.org/
fetch-retries=10
fetch-retry-mintimeout=30000
fetch-retry-maxtimeout=300000
EOF
```

### 프록시 설정 (필요한 경우)
```bash
# EC2에서
export HTTP_PROXY=http://proxy.example.com:8080
export HTTPS_PROXY=http://proxy.example.com:8080

# Docker 빌드 시
docker compose -f docker-compose.prod.yml --env-file .env.prod build \
  --build-arg HTTP_PROXY=$HTTP_PROXY \
  --build-arg HTTPS_PROXY=$HTTPS_PROXY \
  frontend
```

## 확인 및 모니터링

### 빌드 진행 상황 실시간 확인
```bash
# 다른 터미널에서
watch -n 1 'docker ps -a | head -20'
```

### 네트워크 사용량 확인
```bash
# 네트워크 인터페이스 모니터링
sudo iftop -i eth0
```

### Docker 빌드 로그 상세 확인
```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod build \
  --progress=plain \
  --no-cache \
  frontend 2>&1 | tee build.log
```

## 권장 해결 순서

1. **네트워크 연결 확인** (가장 중요)
   ```bash
   curl -v https://registry.npmjs.org/
   ```

2. **보안 그룹 확인**
   - 아웃바운드 HTTPS (443) 허용 확인

3. **수정된 Dockerfile로 재빌드**
   ```bash
   git pull
   docker compose -f docker-compose.prod.yml --env-file .env.prod build --no-cache frontend
   ```

4. **여전히 문제가 있으면**
   - 로컬에서 빌드 후 이미지 전송
   - 또는 EC2 인스턴스 타입 업그레이드 (더 나은 네트워크 성능)

