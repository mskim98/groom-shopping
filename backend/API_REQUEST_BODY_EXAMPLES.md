# API 요청 Body 예시

## 1. 장바구니 담기 API

### 엔드포인트
```
POST /api/cart/add
```

### 헤더
```
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}
```

### 요청 Body 예시
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 2
}
```

### 필드 설명
- `productId` (UUID, 필수): 추가할 제품의 ID
- `quantity` (Integer, 필수): 추가할 수량

### 응답 예시
```json
{
  "cartId": 4,
  "cartItemId": 1,
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 5,
  "message": "장바구니에 추가되었습니다."
}
```

---

## 2. 제품 구매 API

### 엔드포인트
```
POST /api/products/purchase
```

### 헤더
```
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}
```

### 요청 Body 예시
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 1
}
```

### 필드 설명
- `productId` (UUID, 필수): 구매할 제품의 ID
- `quantity` (Integer, 필수): 구매할 수량

### 응답 예시
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 1,
  "remainingStock": 6,
  "stockThresholdReached": true,
  "message": "재고가 6개로 얼마 남지 않았어요"
}
```

---

## 3. Postman 테스트 예시

### 장바구니 담기
1. **Method**: POST
2. **URL**: `http://localhost:8080/api/cart/add`
3. **Headers**:
   - `Content-Type`: `application/json`
   - `Authorization`: `Bearer {토큰}`
4. **Body** (raw JSON):
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 2
}
```

### 제품 구매
1. **Method**: POST
2. **URL**: `http://localhost:8080/api/products/purchase`
3. **Headers**:
   - `Content-Type`: `application/json`
   - `Authorization`: `Bearer {토큰}`
4. **Body** (raw JSON):
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "quantity": 1
}
```

---

## 4. cURL 예시

### 장바구니 담기
```bash
curl -X POST http://localhost:8080/api/cart/add \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{
    "productId": "550e8400-e29b-41d4-a716-446655440000",
    "quantity": 2
  }'
```

### 제품 구매
```bash
curl -X POST http://localhost:8080/api/products/purchase \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{
    "productId": "550e8400-e29b-41d4-a716-446655440000",
    "quantity": 1
  }'
```

---

## 5. 실제 사용 가능한 제품 ID 확인

데이터베이스에서 제품 ID를 확인하려면:
```sql
SELECT id, name, stock, price 
FROM product 
WHERE is_active = true 
LIMIT 5;
```

