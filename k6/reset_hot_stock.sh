#!/usr/bin/env bash
# 핫 상품 재고를 DB·Redis 모두 10000 으로 리셋한다.
# StockWarmUpRunner.initStock 이 SETNX 가 아니라 plain set 이므로 이 방식이 재기동과 동등하다.
set -e
cd /Users/mskim/Desktop/PJ/groom-shopping
ID=550e8400-e29b-41d4-a716-446655440000
U=$(grep POSTGRES_USER .env | cut -d= -f2 | tr -d '\r')
D=$(grep POSTGRES_DB .env | cut -d= -f2 | tr -d '\r')
P=$(grep POSTGRES_PASSWORD .env | cut -d= -f2 | tr -d '\r')
docker exec -e PGPASSWORD="$P" dev-db psql -U "$U" -d "$D" -tAc \
  "UPDATE product SET stock=10000, is_active=true, status='AVAILABLE' WHERE id='$ID';" >/dev/null
docker exec dev-redis redis-cli SET product:stock:$ID 10000 >/dev/null
echo -n "DB="; docker exec -e PGPASSWORD="$P" dev-db psql -U "$U" -d "$D" -tAc "SELECT stock FROM product WHERE id='$ID';"
echo -n "REDIS="; docker exec dev-redis redis-cli GET product:stock:$ID
