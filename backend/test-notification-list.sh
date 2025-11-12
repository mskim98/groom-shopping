#!/bin/bash

echo "=========================================="
echo "📋 전체 알림 조회 테스트"
echo "=========================================="
echo ""

# 1. 로그인하여 JWT 토큰 받기
echo "1️⃣ 로그인 중..."
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user123@test.com",
    "password": "1234"
  }')

echo "로그인 응답:"
echo "$LOGIN_RESPONSE" | jq '.'
echo ""

# JWT 토큰 추출
ACCESS_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken // empty')

if [ -z "$ACCESS_TOKEN" ] || [ "$ACCESS_TOKEN" == "null" ]; then
  echo "❌ 로그인 실패: JWT 토큰을 받을 수 없습니다."
  exit 1
fi

echo "✅ 로그인 성공! JWT 토큰: ${ACCESS_TOKEN:0:50}..."
echo ""

# 2. 전체 알림 조회
echo "2️⃣ 전체 알림 조회 중..."
NOTIFICATION_RESPONSE=$(curl -s -X GET http://localhost:8080/api/v1/notification \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

echo "전체 알림 조회 응답:"
echo "$NOTIFICATION_RESPONSE" | jq '.'

# 알림 개수 확인
NOTIFICATION_COUNT=$(echo "$NOTIFICATION_RESPONSE" | jq 'length // 0')
echo ""
echo "📊 알림 개수: $NOTIFICATION_COUNT개"
echo ""

# 알림 상세 정보 출력
if [ "$NOTIFICATION_COUNT" -gt 0 ]; then
  echo "📝 알림 상세 정보:"
  echo "$NOTIFICATION_RESPONSE" | jq -r '.[] | "  - ID: \(.id) | 읽음: \(.isRead) | 메시지: \(.message) | 생성일: \(.createdAt)"'
fi

echo ""
echo "=========================================="
echo "✅ 테스트 완료!"
echo "=========================================="
