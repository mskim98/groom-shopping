#!/bin/bash

# EC2 배포 스크립트
# 사용법: ./deploy.sh

set -e

echo "🚀 EC2 배포를 시작합니다..."

# .env.prod 파일 확인 및 생성
if [ ! -f .env.prod ]; then
    echo "📝 .env.prod 파일이 없습니다. 기본 템플릿으로 생성합니다..."
    
    # EC2 공개 IP 가져오기
    EC2_IP=$(curl -s ifconfig.me 2>/dev/null || echo "your-ec2-public-ip")
    
    cat > .env.prod << EOF
# Database Configuration
POSTGRES_USER=groom
POSTGRES_PASSWORD=groom123
POSTGRES_DB=groom_shopping

# Kafka Configuration
# EC2 인스턴스의 공개 IP 또는 도메인을 설정하세요
KAFKA_ADVERTISED_HOST=${EC2_IP}

# AWS Configuration
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=goorm-shopping-s3
AWS_ACCESS_KEY=
AWS_SECRET_KEY=

# Toss Payments Configuration
TOSS_SECRET_KEY=test_gsk_docs_OaPz8L5KdmQXkzRz3y47BMw6
TOSS_API_URL=https://api.tosspayments.com
NEXT_PUBLIC_TOSS_CLIENT_KEY=test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm
EOF
    
    chmod 600 .env.prod
    echo "✅ .env.prod 파일이 생성되었습니다."
    echo "⚠️  중요: .env.prod 파일의 실제 값들(특히 비밀번호)을 환경에 맞게 수정하세요!"
    echo ""
fi

# Docker 및 Docker Compose 설치 확인
if ! command -v docker &> /dev/null; then
    echo "❌ Docker가 설치되어 있지 않습니다."
    echo "Docker 설치 가이드: https://docs.docker.com/engine/install/"
    exit 1
fi

# Docker Compose 명령어 확인 (V2 우선, V1 호환)
if docker compose version &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker compose"
elif command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker-compose"
else
    echo "❌ Docker Compose가 설치되어 있지 않습니다."
    exit 1
fi

echo "📦 Docker Compose 명령어: $DOCKER_COMPOSE_CMD"

# 기존 컨테이너 중지 및 제거
echo "📦 기존 컨테이너를 중지하고 제거합니다..."
$DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod down || true

# 실행 중인 빌드 프로세스 중단 (있는 경우)
echo "🛑 실행 중인 빌드 프로세스를 확인하고 중단합니다..."
$DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod down || true
docker ps -a | grep -E "prod-|build" | awk '{print $1}' | xargs -r docker stop || true

# 이미지 빌드 및 컨테이너 시작 (프론트엔드만 명시적으로 빌드)
echo "🔨 이미지를 빌드하고 컨테이너를 시작합니다..."
echo "📦 프론트엔드만 빌드합니다 (백엔드는 제외)..."
$DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod build frontend
$DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod up -d

# 컨테이너 상태 확인
echo "⏳ 컨테이너가 시작될 때까지 대기합니다..."
sleep 10

# 컨테이너 상태 출력
echo "📊 컨테이너 상태:"
$DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod ps

# 로그 확인
echo "📝 최근 로그 (프론트엔드):"
$DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod logs --tail=50 frontend

echo ""
echo "✅ 배포가 완료되었습니다!"
echo "🌐 서비스는 http://$(curl -s ifconfig.me || echo 'your-ec2-ip'):80 에서 접근 가능합니다."
echo ""
echo "📋 유용한 명령어:"
echo "  - 로그 확인: $DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod logs -f [service_name]"
echo "  - 컨테이너 재시작: $DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod restart [service_name]"
echo "  - 서비스 중지: $DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod down"
echo "  - 서비스 중지 및 볼륨 삭제: $DOCKER_COMPOSE_CMD -f docker-compose.prod.yml --env-file .env.prod down -v"

