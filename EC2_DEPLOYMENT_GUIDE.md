# EC2 배포 가이드

이 가이드는 AWS EC2 인스턴스를 사용하여 애플리케이션을 배포하는 방법을 설명합니다.

## 사전 준비사항

### 1. AWS EC2 인스턴스 설정

1. **EC2 인스턴스 생성**
   - Amazon Linux 2023 또는 Ubuntu 22.04 LTS 권장
   - 최소 사양: 2 vCPU, 4GB RAM, 20GB 스토리지
   - 보안 그룹에서 다음 포트 열기:
     - 80 (HTTP)
     - 443 (HTTPS, 선택사항)
     - 22 (SSH)
     - 8080 (백엔드, 선택사항 - nginx를 통해만 접근하는 경우 불필요)

2. **EC2 인스턴스에 접속**
   ```bash
   ssh -i your-key.pem ec2-user@your-ec2-public-ip
   # 또는 Ubuntu의 경우
   ssh -i your-key.pem ubuntu@your-ec2-public-ip
   ```

### 2. EC2 인스턴스에 필요한 소프트웨어 설치

#### Docker 설치 (Amazon Linux 2023)
```bash
sudo yum update -y
sudo yum install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user
# 로그아웃 후 다시 로그인
```

#### Docker 설치 (Ubuntu)
```bash
# Docker 공식 저장소 추가
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 현재 사용자를 docker 그룹에 추가
sudo usermod -aG docker $USER
newgrp docker

# Docker 서비스 시작 및 자동 시작 설정
sudo systemctl start docker
sudo systemctl enable docker
```

#### Docker Compose 설치 확인
```bash
docker compose version
# 또는
docker-compose --version
```

### 3. 프로젝트 파일 업로드

로컬 머신에서 EC2로 프로젝트 파일을 업로드합니다:

```bash
# SCP를 사용한 업로드 (로컬에서 실행)
scp -i your-key.pem -r /path/to/groom-shopping4 ec2-user@your-ec2-public-ip:~/
```

또는 Git을 사용하여 EC2에서 직접 클론:

```bash
# EC2 인스턴스에서 실행
# Git 설치
sudo apt-get install -y git  # Ubuntu
# 또는 Amazon Linux의 경우
sudo yum install -y git

# 프로젝트 클론
git clone your-repository-url
cd groom-shopping4
# 또는 프로젝트 디렉토리 이름에 맞게
cd your-project
```

## 배포 단계

### 1. 환경 변수 파일 생성

EC2 인스턴스에서 프로젝트 디렉토리로 이동한 후:

```bash
cd ~/groom-shopping4

# .env.prod 파일 생성
cat > .env.prod << EOF
# Database Configuration
POSTGRES_USER=prod_user
POSTGRES_PASSWORD=your_secure_password_here
POSTGRES_DB=shopping_db_prod

# Kafka Configuration
# EC2 인스턴스의 공개 IP 또는 도메인을 설정하세요
KAFKA_ADVERTISED_HOST=$(curl -s ifconfig.me)

# AWS Configuration
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=goorm-shopping-s3
AWS_ACCESS_KEY=your_aws_access_key
AWS_SECRET_KEY=your_aws_secret_key

# Toss Payments Configuration
TOSS_SECRET_KEY=your_toss_secret_key
TOSS_API_URL=https://api.tosspayments.com
NEXT_PUBLIC_TOSS_CLIENT_KEY=test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm
EOF

# 파일 권한 설정 (보안)
chmod 600 .env.prod
```

**중요**: `.env.prod` 파일의 실제 값들을 환경에 맞게 수정하세요.

### 2. 배포 스크립트 실행

```bash
# 배포 스크립트에 실행 권한 부여
chmod +x deploy.sh

# 배포 실행
./deploy.sh
```

또는 직접 docker compose 명령어 실행:

```bash
# Docker Compose V2 (권장)
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d

# 또는 Docker Compose V1 (구버전 호환)
docker-compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

**참고**: 최신 Docker에서는 `docker compose` (하이픈 없이)를 사용하는 것이 권장됩니다.

### 3. 배포 확인

```bash
# 컨테이너 상태 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod ps

# 백엔드 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f backend

# 프론트엔드 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f frontend

# Nginx 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f nginx
```

### 4. 서비스 접근 확인

브라우저에서 다음 URL로 접근:
- `http://your-ec2-public-ip`
- `http://your-ec2-public-ip/api/v1/actuator/health` (헬스 체크)

## 유용한 명령어

### 컨테이너 관리
```bash
# 모든 서비스 시작
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d

# 특정 서비스만 재시작
docker compose -f docker-compose.prod.yml --env-file .env.prod restart backend

# 서비스 중지
docker compose -f docker-compose.prod.yml --env-file .env.prod stop

# 서비스 중지 및 컨테이너 제거
docker compose -f docker-compose.prod.yml --env-file .env.prod down

# 서비스 중지 및 볼륨까지 삭제 (주의: 데이터 삭제됨)
docker compose -f docker-compose.prod.yml --env-file .env.prod down -v
```

### 로그 확인
```bash
# 모든 서비스 로그
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f

# 특정 서비스 로그
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f [service_name]

# 최근 100줄만 보기
docker compose -f docker-compose.prod.yml --env-file .env.prod logs --tail=100
```

### 이미지 재빌드
```bash
# 특정 서비스만 재빌드
docker compose -f docker-compose.prod.yml --env-file .env.prod build backend

# 모든 서비스 재빌드
docker compose -f docker-compose.prod.yml --env-file .env.prod build --no-cache
```

## 문제 해결

### 포트 충돌
```bash
# 사용 중인 포트 확인
sudo netstat -tulpn | grep :80
sudo netstat -tulpn | grep :8080

# 프로세스 종료 (필요한 경우)
sudo kill -9 <PID>
```

### 컨테이너가 시작되지 않는 경우
```bash
# 컨테이너 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs [service_name]

# 컨테이너 상태 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod ps -a

# 컨테이너 내부 접속
docker exec -it prod-backend sh
```

### 데이터베이스 연결 문제
```bash
# 데이터베이스 컨테이너 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs db

# 데이터베이스 컨테이너에 접속
docker exec -it prod-db psql -U prod_user -d shopping_db_prod
```

### 디스크 공간 부족
```bash
# 사용하지 않는 이미지 삭제
docker image prune -a

# 사용하지 않는 볼륨 삭제
docker volume prune

# 전체 정리 (주의: 모든 중지된 컨테이너, 네트워크, 이미지 삭제)
docker system prune -a
```

## 보안 권장사항

1. **환경 변수 보호**
   - `.env.prod` 파일은 절대 Git에 커밋하지 마세요
   - 파일 권한을 600으로 설정: `chmod 600 .env.prod`

2. **방화벽 설정**
   - EC2 보안 그룹에서 필요한 포트만 열기
   - SSH 접근은 특정 IP에서만 허용

3. **HTTPS 설정** (선택사항)
   - Let's Encrypt를 사용한 SSL 인증서 설정
   - Nginx에 SSL 설정 추가

4. **정기적인 업데이트**
   ```bash
   # 시스템 업데이트
   sudo yum update -y  # Amazon Linux
   # 또는
   sudo apt-get update && sudo apt-get upgrade -y  # Ubuntu
   
   # Docker 이미지 업데이트
   docker compose -f docker-compose.prod.yml --env-file .env.prod pull
   ```

## 모니터링

프로덕션 환경에서는 다음을 모니터링하는 것을 권장합니다:

- 애플리케이션 로그
- 서버 리소스 사용량 (CPU, 메모리, 디스크)
- 데이터베이스 성능
- 네트워크 트래픽

CloudWatch나 다른 모니터링 도구를 사용하여 알림을 설정하세요.

## 자동 배포 설정 (선택사항)

GitHub Actions, GitLab CI/CD, 또는 Jenkins를 사용하여 자동 배포 파이프라인을 구축할 수 있습니다.

## 추가 리소스

- [Docker 공식 문서](https://docs.docker.com/)
- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [AWS EC2 문서](https://docs.aws.amazon.com/ec2/)

