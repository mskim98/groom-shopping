#!/usr/bin/env bash
# nginx/nginx.conf 를 스텁 업스트림 4개(backend1~3 · frontend) 앞에서 실제로 띄워 분산 · 속도 제한 · 스트림 · 전달 주소를 확인한다
#
# 사용: bash nginx/test/limit-smoke.sh
# 환경변수: NGINX_IMAGE (기본 nginx:1.31.6-alpine)
# 만드는 것: 도커 네트워크 r118-t3-net 과 컨테이너 r118-t3-* 5개. 끝나면(실패해도) 모두 지운다
# 지우는 것: 위에서 만든 것과 임시 폴더뿐이다. 다른 컨테이너 · 네트워크는 건드리지 않는다
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONF="$ROOT/nginx/nginx.conf"
IMAGE="${NGINX_IMAGE:-nginx:1.31.6-alpine}"
NET="r118-t3-net"
PROXY="r118-t3-proxy"
STUBS=(backend1 backend2 backend3 frontend)
TMP="$(mktemp -d)"

cleanup() {
  for s in "${STUBS[@]}"; do docker rm -f "r118-t3-$s" >/dev/null 2>&1 || true; done
  docker rm -f "$PROXY" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
now() { python3 -c 'import time; print(time.time())'; }

# 설정에 적힌 속도 · 버스트를 읽어 기대값 계산에 쓴다
RATE="$(sed -n 's/.*limit_req_zone.* rate=\([0-9][0-9]*\)r\/s.*/\1/p' "$CONF" | head -1)"
BURST="$(sed -n 's/.*limit_req zone=[A-Za-z_]* burst=\([0-9][0-9]*\).*/\1/p' "$CONF" | head -1)"
[ -n "$RATE" ] && [ -n "$BURST" ] || fail "nginx.conf 에서 limit_req_zone rate 와 limit_req burst 를 읽지 못했다"

docker network create "$NET" >/dev/null
for s in "${STUBS[@]}"; do
  cat > "$TMP/$s.conf" <<EOF
events {}
http {
  server {
    listen 8080;
    listen 3000;
    location / {
      default_type text/plain;
      add_header X-Upstream $s always;
      return 200 "xff=\$http_x_forwarded_for real=\$http_x_real_ip\n";
    }
  }
}
EOF
  docker run -d --name "r118-t3-$s" --network "$NET" --network-alias "$s" \
    -v "$TMP/$s.conf:/etc/nginx/nginx.conf:ro" "$IMAGE" >/dev/null
done

docker run -d --name "$PROXY" --network "$NET" -p 127.0.0.1::80 \
  -v "$CONF:/etc/nginx/nginx.conf:ro" "$IMAGE" >/dev/null
sleep 1
docker ps --filter "name=$PROXY" --filter status=running -q | grep -q . \
  || { docker logs "$PROXY" >&2 || true; fail "nginx 가 기동하지 못했다"; }
PORT="$(docker port "$PROXY" 80/tcp | head -1 | sed 's/.*://')"
URL="http://127.0.0.1:$PORT"

# (a) 설정 문법
docker exec "$PROXY" nginx -t >/dev/null 2>&1 || fail "(a) nginx -t 실패"
echo "ok (a) nginx -t"

# (e) 클라이언트가 보낸 X-Forwarded-For 는 버려지고 접속 주소로 채워진다 - 속도 제한 전에 1건만 보낸다
body="$(curl -s -H 'X-Forwarded-For: 6.6.6.6' "$URL/api/v1/product")"
xff="$(echo "$body" | sed -n 's/^xff=\([^ ]*\) real=.*/\1/p')"
real="$(echo "$body" | sed -n 's/.* real=\(.*\)$/\1/p')"
[ -n "$xff" ] || fail "(e) 업스트림이 X-Forwarded-For 를 받지 못했다: $body"
case "$xff" in *6.6.6.6*|*,*) fail "(e) 클라이언트 값이 남았다: $xff";; esac
[ "$xff" = "$real" ] || fail "(e) X-Forwarded-For($xff) 가 접속 주소($real)와 다르다"
echo "ok (e) X-Forwarded-For=$xff"

# (d) 스트림 location 은 버퍼링을 끄고 읽기 제한을 길게 둔다
block="$(docker exec "$PROXY" nginx -T 2>/dev/null | awk '/location .*notification\/stream/{f=1} f{print} f&&/}/{exit}')"
[ -n "$block" ] || fail "(d) 스트림 location 이 없다"
echo "$block" | grep -q 'proxy_buffering off' || fail "(d) 스트림 location 에 proxy_buffering off 가 없다"
echo "$block" | grep -q 'proxy_read_timeout' || fail "(d) 스트림 location 에 proxy_read_timeout 이 없다"
echo "ok (d) 스트림 버퍼링 끔"

# (b)(c) 한 주소에서 300건을 한꺼번에 보낸다
start="$(now)"
seq 300 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code} %header{x-upstream}\n' "$URL/api/v1/product" > "$TMP/burst.txt"
end="$(now)"
ok200="$(grep -c '^200 ' "$TMP/burst.txt" || true)"
c429="$(grep -c '^429' "$TMP/burst.txt" || true)"
limit="$(awk -v b="$BURST" -v r="$RATE" -v s="$start" -v e="$end" 'BEGIN{printf "%d", b + r*(e-s) + 1}')"
[ "$ok200" -ge 1 ] && [ "$c429" -ge 1 ] || fail "(b) 200 과 429 가 모두 나와야 한다: 200=$ok200 429=$c429"
[ "$ok200" -le "$limit" ] || fail "(b) 200 응답 $ok200 건이 상한 $limit 을 넘었다"
echo "ok (b) 200=$ok200 429=$c429 상한=$limit"
ups="$(grep '^200 ' "$TMP/burst.txt" | awk '{print $2}' | sort -u | wc -l | tr -d ' ')"
[ "$ups" = "1" ] || fail "(c) 한 주소의 200 응답이 업스트림 $ups 곳에서 왔다"
echo "ok (c) 업스트림 1곳"
echo "PASS"
