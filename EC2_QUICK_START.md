# EC2 빠른 시작 가이드

## 🚀 EC2에서 배포하기 (최소 단계)

### 1단계: EC2 접속
```bash
ssh -i your-key.pem ubuntu@your-ec2-public-ip
```

### 2단계: Docker 설치 (최초 1회만)
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

# Git 설치
sudo apt-get install -y git
```

### 3단계: 프로젝트 클론 (최초 1회만)
```bash
git clone your-github-repository-url
cd groom-shopping4  # 또는 프로젝트 디렉토리 이름
```

### 4단계: 배포 실행
```bash
chmod +x deploy.sh
./deploy.sh
```

**끝!** 🎉

---

## ⚠️ 주의사항

### `.env.prod` 파일 자동 생성
`deploy.sh`가 자동으로 `.env.prod` 파일을 생성하지만, 다음 값들은 **반드시 수정**해야 합니다:

1. **데이터베이스 비밀번호**: `POSTGRES_PASSWORD=groom123` (보안을 위해 변경 권장)
2. **Toss Payments Secret Key**: `TOSS_SECRET_KEY=your_toss_secret_key` (실제 값으로 변경)

### 수정 방법
```bash
# .env.prod 파일 편집
nano .env.prod
# 또는
vi .env.prod

# 수정 후 저장하고 다시 배포
./deploy.sh
```

---

## 📋 배포 후 확인

### 컨테이너 상태 확인
```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

### 로그 확인
```bash
# 모든 서비스 로그
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f

# 특정 서비스 로그
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f backend
```

### 웹사이트 접속
- 메인 페이지: `http://your-ec2-public-ip`
- 헬스 체크: `http://your-ec2-public-ip/api/v1/actuator/health`

---

## 🔄 업데이트 배포

코드가 업데이트된 경우:

```bash
# 프로젝트 디렉토리로 이동
cd ~/groom-shopping4

# 최신 코드 가져오기
git pull

# 재배포
./deploy.sh
```

---

## ❌ 문제 해결

### 포트가 이미 사용 중인 경우
```bash
# 사용 중인 포트 확인
sudo netstat -tulpn | grep :80

# 기존 컨테이너 중지
docker compose -f docker-compose.prod.yml --env-file .env.prod down
```

### 컨테이너가 시작되지 않는 경우
```bash
# 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs

# 특정 서비스 재시작
docker compose -f docker-compose.prod.yml --env-file .env.prod restart backend
```

### 디스크 공간 부족
```bash
# 사용하지 않는 이미지 삭제
docker image prune -a

# 사용하지 않는 볼륨 삭제
docker volume prune
```

---

## 📝 요약

**최초 설정 (1회만):**
1. Docker 설치
2. Git 설치
3. 프로젝트 클론

**매번 배포:**
```bash
./deploy.sh
```

**업데이트 배포:**
```bash
git pull && ./deploy.sh
```

