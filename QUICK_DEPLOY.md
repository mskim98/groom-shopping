# 빠른 배포 가이드

## EC2에서 빠르게 배포하기

### 1. 환경 변수 파일 생성

```bash
cat > .env.prod << EOF
POSTGRES_USER=prod_user
POSTGRES_PASSWORD=your_secure_password
POSTGRES_DB=shopping_db_prod
KAFKA_ADVERTISED_HOST=$(curl -s ifconfig.me)
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=goorm-shopping-s3
AWS_ACCESS_KEY=your_aws_access_key
AWS_SECRET_KEY=your_aws_secret_key
TOSS_SECRET_KEY=your_toss_secret_key
TOSS_API_URL=https://api.tosspayments.com
NEXT_PUBLIC_TOSS_CLIENT_KEY=test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm
EOF

chmod 600 .env.prod
```

### 2. 배포 실행

```bash
chmod +x deploy.sh
./deploy.sh
```

또는 직접 실행:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
```

### 3. 확인

```bash
# 컨테이너 상태
docker compose -f docker-compose.prod.yml --env-file .env.prod ps

# 로그 확인
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f
```

### 4. 접속

- 웹사이트: `http://your-ec2-public-ip`
- 헬스 체크: `http://your-ec2-public-ip/api/v1/actuator/health`

---

자세한 내용은 [EC2_DEPLOYMENT_GUIDE.md](./EC2_DEPLOYMENT_GUIDE.md)를 참조하세요.

