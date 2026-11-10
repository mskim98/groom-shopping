#!/usr/bin/env bash
# T2-5 시험: payment-events 에 메시지 1건을 넣고 recreate-topics.sh 를 돌린 뒤
# 파티션 수(payment-events 12 · 나머지 6개 1), 12개 파티션 모두 끝 오프셋 0, 시험이 만든 표식 토픽 · drawing-execute-topic 이 남았는지 본다
# 환경 변수: SEED_KAFKA_CONTAINER(dev-kafka — 이 시험은 r118-t2-kafka 를 넘긴다) SEED_PG_CONTAINER SEED_REDIS_PORT
# 지우는 것: recreate-topics.sh 가 다루는 7개 토픽, 이 시험이 만든 t2-marker-* 토픽, 이 시험이 만든 drawing-execute-topic
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SEED="$(dirname "$HERE")"
. "$SEED/lib.sh"

MARK="t2-marker-$$"
DRAW=drawing-execute-topic
CREATED_DRAW=0
FAILS=0
fail() { echo "FAIL(test) $*"; FAILS=$((FAILS + 1)); }
cleanup() {
  seed_kafka kafka-topics --delete --topic "$MARK" > /dev/null 2>&1 || true
  [ "$CREATED_DRAW" = 0 ] || seed_kafka kafka-topics --delete --topic "$DRAW" > /dev/null 2>&1 || true
}
trap cleanup EXIT

topics() { seed_kafka kafka-topics --list; }
partitions() { seed_kafka kafka-topics --describe --topic "$1" | grep -o 'PartitionCount: *[0-9]*' | grep -o '[0-9]*$'; }

if ! topics | grep -qx "$DRAW"; then
  seed_kafka kafka-topics --create --topic "$DRAW" --partitions 1 --replication-factor 1 > /dev/null
  CREATED_DRAW=1
fi
seed_kafka kafka-topics --create --topic "$MARK" --partitions 2 --replication-factor 1 > /dev/null
# payment-events 를 3 파티션으로 만들어 12 가 아닌 상태에서 시작한다(이미 있으면 그대로 두고 메시지만 넣는다)
topics | grep -qx payment-events || seed_kafka kafka-topics --create --topic payment-events --partitions 3 --replication-factor 1 > /dev/null
echo 'k:{"t2":"before-recreate"}' | docker exec -i "$SEED_KAFKA_CONTAINER" kafka-console-producer --bootstrap-server localhost:9093 --topic payment-events --property parse.key=true --property key.separator=: > /dev/null 2>&1
before="$(seed_kafka kafka-get-offsets --topic payment-events | awk -F: '{s+=$3} END {print s+0}')"
[ "$before" -ge 1 ] || fail "test produced no message into payment-events (sum of end offsets $before)"

echo "== recreate-topics.sh"
bash "$SEED/recreate-topics.sh"

[ "$(partitions payment-events)" = 12 ] || fail "payment-events partitions $(partitions payment-events), want 12"
for t in payment-events-dlq coupon-delay-events payment-compensation-dlq stock-threshold-events coupon-activate-events coupon-delay-dlq; do
  topics | grep -qx "$t" || { fail "$t is missing"; continue; }
  [ "$(partitions "$t")" = 1 ] || fail "$t partitions $(partitions "$t"), want 1"
done

offs="$(seed_kafka kafka-get-offsets --topic payment-events)"
[ "$(printf '%s\n' "$offs" | wc -l | tr -d ' ')" = 12 ] || fail "payment-events offsets listed for $(printf '%s\n' "$offs" | wc -l | tr -d ' ') partitions, want 12"
[ -z "$(printf '%s\n' "$offs" | awk -F: '$3 != 0')" ] || fail "payment-events has an end offset other than 0: $(printf '%s' "$offs" | tr '\n' ' ')"

topics | grep -qx "$MARK" || fail "marker topic $MARK was removed"
topics | grep -qx "$DRAW" || fail "$DRAW was removed"

if [ "$FAILS" -ne 0 ]; then echo "topics-test: $FAILS failure(s)"; exit 1; fi
echo "topics-test: OK"
