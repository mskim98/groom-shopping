#!/bin/bash

echo "=========================================="
echo "📋 전체 알림 조회 테스트 (디버그 모드)"
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

# 2. 전체 알림 조회 (상세 응답)
echo "2️⃣ 전체 알림 조회 중..."
NOTIFICATION_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET http://localhost:8080/api/v1/notification \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

HTTP_STATUS=$(echo "$NOTIFICATION_RESPONSE" | grep "HTTP_STATUS" | cut -d: -f2)
BODY=$(echo "$NOTIFICATION_RESPONSE" | sed '/HTTP_STATUS/d')

echo "HTTP 상태 코드: $HTTP_STATUS"
echo ""
echo "응답 본문:"
echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"

echo ""
echo "=========================================="
if [ "$HTTP_STATUS" == "200" ]; then
  echo "✅ 테스트 성공!"
else
  echo "❌ 테스트 실패 (HTTP $HTTP_STATUS)"
fi
echo "=========================================="
