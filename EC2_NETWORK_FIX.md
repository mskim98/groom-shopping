# EC2 네트워크 문제 해결 가이드

## npm 네트워크 오류 해결

EC2에서 Docker 빌드 중 `npm ci` 실행 시 네트워크 오류가 발생하는 경우 해결 방법입니다.

### 즉시 해결 방법

#### 1. EC2 보안 그룹 확인
EC2 인스턴스의 보안 그룹에서 아웃바운드 트래픽이 허용되어 있는지 확인:
- 모든 트래픽 (0.0.0.0/0) 허용
- 또는 최소한 HTTPS (443) 포트 허용

#### 2. 네트워크 연결 확인
```bash
# npm 레지스트리 접근 확인
curl -I https://registry.npmjs.org/

# DNS 확인
nslookup registry.npmjs.org
```

#### 3. npm 캐시 정리 후 재시도
```bash
# Docker 빌드 캐시 없이 재빌드
docker compose -f docker-compose.prod.yml --env-file .env.prod build --no-cache frontend
```

#### 4. 수동으로 npm 설치 후 재빌드
```bash
# EC2에서 직접 npm 설치 테스트
cd ~/groom-shopping4/frontend
npm install

# 정상 작동하면 Docker 빌드 재시도
cd ~/groom-shopping4
docker compose -f docker-compose.prod.yml --env-file .env.prod build frontend
```

### Dockerfile 수정 사항

Dockerfile에 다음이 추가되었습니다:
- npm 재시도 설정 (5회)
- 네트워크 타임아웃 증가 (5분)
- `npm ci` 실패 시 `npm install`로 자동 대체

### 추가 해결 방법

#### npm 레지스트리 미러 사용 (선택사항)
```bash
# .npmrc 파일 생성 (프로젝트 루트 또는 frontend 디렉토리)
echo "registry=https://registry.npmjs.org/" > frontend/.npmrc
echo "fetch-retries=5" >> frontend/.npmrc
echo "fetch-retry-mintimeout=20000" >> frontend/.npmrc
echo "fetch-retry-maxtimeout=120000" >> frontend/.npmrc
```

#### 프록시 설정 (회사 네트워크인 경우)
```bash
# EC2에서 프록시 설정
export HTTP_PROXY=http://proxy.example.com:8080
export HTTPS_PROXY=http://proxy.example.com:8080
export NO_PROXY=localhost,127.0.0.1

# Docker 빌드 시 프록시 전달
docker compose -f docker-compose.prod.yml --env-file .env.prod build \
  --build-arg HTTP_PROXY=$HTTP_PROXY \
  --build-arg HTTPS_PROXY=$HTTPS_PROXY \
  frontend
```

### 빌드 재시도

```bash
# 1. 기존 빌드 중단
docker ps -q | xargs docker stop 2>/dev/null || true

# 2. 빌드 캐시 정리 (선택사항)
docker builder prune -f

# 3. 프론트엔드만 재빌드
docker compose -f docker-compose.prod.yml --env-file .env.prod build frontend

# 4. 서비스 시작
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

### 확인

```bash
# 빌드 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f frontend

# 컨테이너 상태 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

